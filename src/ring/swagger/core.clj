(ns ring.swagger.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [schema.macros :as sm]
            [plumbing.core :refer :all]
            [schema.utils :as su]
            [ring.swagger.schema :as schema]
            [ring.swagger.coerce :as coerce]
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [cheshire.generate :refer [add-encoder]]
            [camel-snake-kebab.core :refer [->camelCase]])
  (:import [com.fasterxml.jackson.core JsonGenerator]))

;;
;; Models
;;

(s/defschema Route {:method   s/Keyword
                    :uri      [s/Any]
                    :metadata {s/Keyword s/Any}})

(s/defschema ResponseMessage {:code Long
                              (s/optional-key :message) String
                              (s/optional-key :responseModel) s/Any})

;;
;; JSON Encoding
;;

(add-encoder schema.utils.ValidationError
  (fn [x ^JsonGenerator jg]
    (.writeString jg
      (str (su/validation-error-explain x)))))

(defn date-time-encoder [x ^JsonGenerator jg]
  (.writeString jg (coerce/unparse-date-time x)))

(add-encoder java.util.Date date-time-encoder)
(add-encoder org.joda.time.DateTime date-time-encoder)

(add-encoder org.joda.time.LocalDate
  (fn [x ^JsonGenerator jg]
    (.writeString jg (coerce/unparse-date x))))

;;
;; Schema transformations
;;

(defn- plain-map?
  [x]
  (or
    (instance? clojure.lang.APersistentMap x)
    (instance? flatland.ordered.map.OrderedMap x)))

(defn- full-name [path] (->> path (map name) (map ->CamelCase) (apply str) symbol))
(defn- collect-schemas [keys schema]
  (cond
    (plain-map? schema)
    (if (and (seq (pop keys)) (s/schema-name schema))
      schema
      (with-meta
        (into (empty schema)
              (for [[k v] schema
                    :when (jsons/not-predicate? k)
                    :let [keys (conj keys (s/explicit-schema-key k))]]
                [k (collect-schemas keys v)]))
        {:name (full-name keys)}))

    (valid-container? schema)
    (contain schema (collect-schemas keys (first schema)))

    :else schema))

(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  [schema]
  (collect-schemas [(or (s/schema-name schema) (gensym "schema"))] schema))

(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:id (s/schema-name schema)
       :properties (jsons/properties schema)
       :required required})))

(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when-let [schema (s/schema-name x)]
          (swap! schemas assoc schema (if (var? x) @x x)))
        x)
      x)
    @schemas))

(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt key (comp transform val)))
       (into {})))

(defn extract-models [details]
  (let [route-meta (->> details
                        :routes
                        (map :metadata))
        return-models (->> route-meta
                           (keep :return)
                           flatten)
        body-models (->> route-meta
                         (mapcat :parameters)
                         (filter (fn-> :type (= :body)))
                         (keep :model)
                         flatten)
        response-models (->> route-meta
                             (mapcat :responseMessages)
                             (keep :responseModel)
                             flatten)
        all-models (->> (concat body-models return-models response-models)
                        flatten
                        (map with-named-sub-schemas))]
    (into {} (map (juxt s/schema-name identity) all-models))))

;;
;; Route generation
;;

(defn path-params [s]
  (map (comp keyword second) (re-seq #":(.[^:|(/]*)[/]?" s)))

(defn string-path-parameters [uri]
  (let [params (path-params uri)]
    (if (seq params)
      {:type :path
       :model (zipmap params (repeat String))})))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn generate-nick [{:keys [method uri]}]
  (-> (str (name method) " " uri)
    (str/replace #"/" " ")
    (str/replace #"-" "_")
    (str/replace #":" " by ")
    ->camelCase))

(def swagger-defaults      {:swaggerVersion "1.2" :apiVersion "0.0.1"})
(def resource-defaults     {:produces ["application/json"]
                            :consumes ["application/json"]})
(def api-declaration-keys  [:title :description :termsOfServiceUrl :contact :license :licenseUrl])

(defn join-paths
  "Join several paths together with \"/\". If path ends with a slash,
   another slash is not added."
  [& paths]
  (str/replace (str/replace (str/join "/" (remove nil? paths)) #"([/]+)" "/") #"/$" ""))

(defn context
  "Context of a request. Defaults to \"\", but has the
   servlet-context in the legacy app-server environments."
  [{:keys [servlet-context]}]
  (if servlet-context (.getContextPath servlet-context) ""))

(defn basepath
  "extract a base-path from ring request. Doesn't return default ports
   and reads the header \"x-forwarded-proto\" only if it's set to value
   \"https\". (e.g. your ring-app is behind a nginx reverse https-proxy).
   Adds possible servlet-context when running in legacy app-server."
  [{:keys [scheme server-name server-port headers] :as request}]
  (let [x-forwarded-proto (headers "x-forwarded-proto")
        context (context request)
        scheme (if (= x-forwarded-proto "https") "https" (name scheme))
        port (if (#{80 443} server-port) "" (str ":" server-port))]
    (str scheme "://" server-name port context)))

;;
;; Convert parameters
;;

(defmulti ^:private extract-parameter
  (fn [{:keys [type]}]
    type))

(defmethod extract-parameter :body [{:keys [model type]}]
  (if model
    (vector
      (jsons/->parameter {:paramType type
                          :name (some-> model schema/extract-schema-name str/lower-case)}
                         (jsons/->json model :top true)))))

(defmethod extract-parameter :default [{:keys [model type] :as it}]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key (eval k))]]
      (jsons/->parameter {:paramType type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (jsons/->json v)))))

(defn convert-parameters [parameters]
  (mapcat extract-parameter parameters))

(sm/defn ^:always-validate convert-response-messages [messages :- [ResponseMessage]]
  (for [{:keys [responseModel] :as message} messages]
    (if (and responseModel (schema/named-schema? responseModel))
      (update-in message [:responseModel] (fn [x] (:type (jsons/->json x :top true))))
      (dissoc message :responseModel))))

;;
;; Routing
;;

(defn api-listing [parameters swagger]
  (response
    (merge
      swagger-defaults
      (select-keys parameters [:apiVersion])
      {:info (select-keys parameters api-declaration-keys)
       :apis (for [[api details] swagger]
               {:path (str "/" (name api))
                :description (or (:description details) "")})})))

(defn api-declaration [parameters swagger api basepath]
  (if-let [details (and swagger (swagger api))]
    (response
      (merge
        swagger-defaults
        resource-defaults
        (select-keys parameters [:apiVersion :produces :consumes])
        {:basePath basepath
         :resourcePath "/"
         :models (transform-models (extract-models details))
         :apis (for [{:keys [method uri metadata] :as route} (:routes details)
                     :let [{:keys [return summary notes nickname parameters responseMessages]} metadata]]
                 {:path (swagger-path uri)
                  :operations [(merge
                                 (jsons/->json return :top true)
                                 {:method (-> method name .toUpperCase)
                                  :summary (or summary "")
                                  :notes (or notes "")
                                  :nickname (or nickname (generate-nick route))
                                  :responseMessages (convert-response-messages responseMessages)
                                  :parameters (convert-parameters parameters)})]})}))))

;;
;; 2.0
;;

;;
;; schemas
;;

(defn regexp [r n] (s/pred (partial re-find r) n))
(defn valid-response-key? [x] (or (= :default x) (integer? x)))

(def VendorExtension {(s/pred (fn->> name (re-find #"^x-")) "vendor extension") s/Any})

(s/defschema ExternalDocs {:url s/Str
                           (s/optional-key :description) s/Str})

(s/defschema Info (merge
                    VendorExtension
                    {:version s/Str
                     :title   s/Str
                     (s/optional-key :description) s/Str
                     (s/optional-key :termsOfService) s/Str
                     (s/optional-key :contact) {(s/optional-key :name) s/Str
                                                (s/optional-key :url) s/Str
                                                (s/optional-key :email) s/Str}
                     (s/optional-key :licence) {:name s/Str
                                                (s/optional-key :url) s/Str}}))

(s/defschema Schema s/Any)
(s/defschema Scheme (s/enum :http :https :ws :wss))
(s/defschema SerializableType {(s/optional-key :type) (s/enum :string :number :boolean :integer :array :file)
                               (s/optional-key :format) s/Str
                               (s/optional-key :items) s/Any
                               (s/optional-key :collectionFormat) s/Str})

(s/defschema Example s/Any)   ; TODO
(s/defschema Schema s/Any)    ; TODO

(s/defschema Parameter
             (merge
                         VendorExtension
                         {:name s/Str
                          :in (s/enum :query, :header, :path, :formData)
                          (s/optional-key :description) s/Str
                          (s/optional-key :required) s/Bool
                          (s/optional-key :type) (s/enum :string, :number, :boolean, :integer, :array)
                          (s/optional-key :format) s/Str
                          (s/optional-key :items) s/Any ; TODO https://github.com/reverb/swagger-spec/blob/master/schemas/v2.0/schema.json#L401
                          (s/optional-key :collectionFormat) s/Str}))

(s/defschema Response {:description s/Str
                       (s/optional-key :schema) Schema
                       (s/optional-key :headers) [SerializableType]
                       (s/optional-key :examples) Example})

(s/defschema Responses (merge
                         ;VendorExtension TODO: More than one non-optional/required key schemata
                         {(s/pred valid-response-key?) Response}))


(s/defschema Ref {:$ref s/Str})

(s/defschema Operation {(s/optional-key :tags) [s/Str]
                        (s/optional-key :summary) s/Str
                        (s/optional-key :description) s/Str
                        (s/optional-key :externalDocs) ExternalDocs
                        (s/optional-key :operationId) s/Str
                        (s/optional-key :consumes) [s/Str]
                        (s/optional-key :produces) [s/Str]
                        (s/optional-key :parameters) [(s/either Parameter Ref)] ;TODO https://github.com/reverb/swagger-spec/blob/master/schemas/v2.0/schema.json#L236
                        :responses Responses
                        (s/optional-key :schemes) [Scheme]
                        ;(s/optional-key :security) s/Any
                        })
(s/defschema PathItem {(s/optional-key :ref) s/Str
                       (s/optional-key :get) Operation
                       (s/optional-key :put) Operation
                       (s/optional-key :post) Operation
                       (s/optional-key :delete) Operation
                       (s/optional-key :options) Operation
                       (s/optional-key :head) Operation
                       (s/optional-key :patch) Operation
                       (s/optional-key :parameters) [Parameter]})
(s/defschema Paths {(regexp #"^/.*[^\/]$" "valid path") PathItem})
(s/defschema Definitions {s/Keyword {s/Keyword s/Any}})

#_(s/defschema Parameters s/Any)
#_(s/defschema Security s/Any)
#_(s/defschema Tag s/Any)

(s/defschema SwaggerDocs {:swagger (s/enum 2.0)
                          :info Info
                          ;(s/optional-key :externalDocs) ExternalDocs
                          (s/optional-key :host) s/Str
                          (s/optional-key :basePath) s/Str
                          (s/optional-key :schemes) [Scheme]
                          (s/optional-key :consumes) [s/Str]
                          (s/optional-key :produces) [s/Str]
                          :paths Paths
                          (s/optional-key :definitions) Definitions
                          ;(s/optional-key :parameters) Parameters
                          ;(s/optional-key :responses) Responses
                          ;(s/optional-key :security) Security
                          ;(s/optional-key :tags) [Tag]
                          })

;;
;; defaults
;;

(def info-defaults {:version "0.0.1"
                    :title ""})

(defn swagger-docs [swagger #_basepath]
  (response
    {:swagger 2.0
     :info (merge
             info-defaults
             (:info swagger))
     :paths (:paths swagger)}))

(s/validate SwaggerDocs
            (:body (swagger-docs
                     {:info {:version "version"
                             :title "title"
                             :description "description"
                             :termsOfService "jeah"
                             :contact {:name "name"
                                       :url "url"
                                       :email "email"}
                             :licence {:name "name"
                                       :url "url"}
                             :x-kikka "jeah"}
                      :paths {"/api" {:get {:description "description"
                                            :operationId "operationId"
                                            :produces ["produces"]
                                            :parameters [{:name "name"
                                                          :in :query
                                                          :description "description"
                                                          :required true
                                                          :type :integer
                                                          :format "format"}]
                                            :responses {200 {:description "description"}
                                                        :default {:description "description"}}}}}})))
