(ns reitit-extras.core
  "Useful Reitit router middlewares and helpers."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup2.core :as hiccup]
            [muuntaja.core :as muuntaja-core]
            [reitit.coercion :as coercion]
            [reitit.coercion.malli :as coercion-malli]
            [reitit.core :as reitit]
            [reitit.dev.pretty :as pretty]
            [reitit.impl :as impl]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as ring-multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as ring-parameters]
            [reitit.spec :as rs]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.cookies :as ring-cookies]
            [ring.middleware.default-charset :as default-charset]
            [ring.middleware.gzip :as gzip]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.session :as ring-session]
            [ring.middleware.session.cookie :as ring-session-cookie]
            [ring.middleware.ssl :as ring-ssl]
            [ring.middleware.x-headers :as x-headers]
            [ring.util.response :as response])
  (:import (java.security MessageDigest)))

; Middlewares

(defn wrap-context
  "Add system dependencies of handler to request as a context key."
  [handler context]
  (fn [request]
    (-> request
        (assoc :context context)
        (handler))))

(defn wrap-reload
  "Reload ring handler on every request. Useful in dev mode."
  [f]
  ; require reloader locally to exclude dev dependency from prod build
  (let [reload! ((requiring-resolve 'ring.middleware.reload/reloader) ["src"] true)]
    (fn
      ([request]
       (reload!)
       ((f) request))
      ([request respond raise]
       (reload!)
       ((f) request respond raise)))))

(defn string->16-byte-array [s]
  (let [digest (MessageDigest/getInstance "MD5")]
    (.digest digest (.getBytes s "UTF-8"))))

; URLs

(defn get-route
  "Return the API route by its name, with optional path and query parameters."
  ([router route-name]
   (get-route router route-name {}))
  ([router route-name {:keys [path query]}]
   (-> router
       (reitit/match-by-name route-name path)
       (reitit/match->path query))))

; Exceptions

(defn- get-error-path
  [exception]
  (mapv
    (comp #(str/join ":" %) :at)
    (:via (Throwable->map exception))))

(defn- default-error-handler
  [error-type exception _request]
  {:status 500
   :body {:type error-type
          :path (get-error-path exception)
          :error (ex-data exception)
          :details (ex-message exception)}})

(defn- wrap-exception
  [handler e request]
  (log/error e (pr-str (:request-method request) (:uri request)) (ex-message e))
  (handler e request))

(def exception-middleware
  "Common exception middleware to handle all errors."
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {; override the default handler
       ::exception/default (partial default-error-handler "UnexpectedError")

       ; print stack-traces for all exceptions
       ::exception/wrap wrap-exception})))

; Handlers

(defn csrf-token
  "Return the CSRF token value."
  []
  (force anti-forgery/*anti-forgery-token*))

(defn csrf-token-html
  "Return a hidden input field with the CSRF token."
  []
  [:input {:type "hidden"
           :name "__anti-forgery-token"
           :id "__anti-forgery-token"
           :value (csrf-token)}])

(defn csrf-token-json
  "Return a JSON object as string with the CSRF token.
   Useful for headers in AJAX requests."
  []
  (format "{\"X-CSRF-Token\": \"%s\"}" (csrf-token)))

(def ^:private DEFAULT-CACHE-365D "public,max-age=31536000,immutable")

(defn create-resource-handler-cached
  "Return resource handler with optional Cache-Control header."
  [{:keys [cached? cache-control]
    :or {cached? false}
    :as opts}]
  (letfn [(resource-response-cached-fn
            ([path]
             (resource-response-cached-fn path {}))
            ([path options]
             (-> (response/resource-response path options)
                 (response/header "Cache-Control" (or cache-control DEFAULT-CACHE-365D)))))]
    (let [response-fn (if cached?
                        resource-response-cached-fn
                        response/resource-response)]
      (-> response-fn
          (ring/-create-file-or-resource-handler opts)
          (gzip/wrap-gzip)))))

(defn render-html
  "Render hiccup content as HTML response."
  [content]
  (-> (str "<!DOCTYPE html>\n" (hiccup/html content))
      (response/response)
      (response/header "Content-Type" "text/html")))

(defn wrap-xss-protection
  ([handler]
   (wrap-xss-protection handler {}))
  ([handler _options]
   (x-headers/wrap-xss-protection handler true nil)))

(defn- ->keyword-keys
  [m]
  (reduce-kv
    (fn [acc k v]
      (assoc acc (if (keyword? k) k (keyword k)) v))
    {}
    m))

(def non-throwing-coerce-request-middleware
  "Middleware for validating and coercing request parameters based on route specs.
  Expects a `:coercion` of type `reitit.coercion/Coercion` and `:parameters` defined in
  route data. If either is missing, the middleware will not mount. Validates request
  parameters against specs and coerces them to the correct types. If coercion fails,
  does not throw an exception but passes a request with `:errors` key containing.
  Based on `reitit.ring.coercion/coerce-request-middleware` but does not throw exceptions."
  {:name ::ring-coercion/coerce-request
   :spec ::rs/parameters
   :compile (fn [{:keys [coercion parameters request]} opts]
              (cond
                ;; no coercion, skip
                (not coercion) nil
                ;; just coercion, don't mount
                (not (or parameters request)) {}
                ;; mount
                :else
                (if-let [coercers (coercion/request-coercers coercion parameters request opts)]
                  (fn [handler]
                    (fn
                      ([request]
                       (let [coerced (try
                                       (coercion/coerce-request coercers request)
                                       (catch Exception e
                                         {:errors (coercion/encode-error (ex-data e))}))
                             {:keys [form-params path-params query-params]} request]
                         (if (contains? coerced :errors)
                           (handler (-> request
                                        (impl/fast-assoc :errors (:errors coerced))
                                        (impl/fast-assoc :parameters (cond-> {}
                                                                       (seq form-params) (assoc :form (->keyword-keys form-params))
                                                                       (seq path-params) (assoc :path (->keyword-keys path-params))
                                                                       (seq query-params) (assoc :query (->keyword-keys query-params))))))
                           (handler (impl/fast-assoc request :parameters coerced)))))
                      ([request respond raise]
                       (let [coerced (try
                                       (coercion/coerce-request coercers request)
                                       (catch Exception e
                                         {:errors (coercion/encode-error (ex-data e))}))
                             {:keys [form-params path-params query-params]} request]
                         (if (contains? coerced :errors)
                           (handler (-> request
                                        (impl/fast-assoc :errors (:errors coerced))
                                        (impl/fast-assoc :parameters (cond-> {}
                                                                       (seq form-params) (assoc :form (->keyword-keys form-params))
                                                                       (seq path-params) (assoc :path (->keyword-keys path-params))
                                                                       (seq query-params) (assoc :query (->keyword-keys query-params)))))
                                    respond raise)
                           (handler (impl/fast-assoc request :parameters coerced) respond raise))))))
                  {})))})

(defn handler-ssr
  "Return main application handler for server-side rendering.

  DEPRECATED: Define the server directly in a project instead.
  Can be used as an example of how to create a handler with Reitit."
  [{:keys [routes default-handlers session-store middlewares]
    :or {middlewares []}}
   {:keys [options]
    :as context}]
  (let [session-store (or session-store
                          (ring-session-cookie/cookie-store
                            {:key (string->16-byte-array (:session-secret-key options))}))]
    (ring/ring-handler
      (ring/router
        routes
        {:exception pretty/exception
         :data {:muuntaja muuntaja-core/instance
                :coercion coercion-malli/coercion
                :middleware (vec
                              (concat
                                middlewares
                                [[x-headers/wrap-content-type-options :nosniff]
                                 [x-headers/wrap-frame-options :sameorigin]
                                 ring-ssl/wrap-hsts
                                 wrap-xss-protection
                                 not-modified/wrap-not-modified
                                 content-type/wrap-content-type
                                 [default-charset/wrap-default-charset "utf-8"]
                                 ring-cookies/wrap-cookies
                                 [ring-session/wrap-session
                                  {:cookie-attrs {:secure true
                                                  :http-only true}
                                   :flash true
                                   :store session-store}]
                                ; add handler options to request
                                 [wrap-context context]
                                ; parse any request parameters
                                 ring-parameters/parameters-middleware
                                 ring-multipart/multipart-middleware
                                 nested-params/wrap-nested-params
                                 keyword-params/wrap-keyword-params
                                ; negotiate request and response
                                 muuntaja/format-middleware
                                ; Check CSRF token
                                ; add call (csrf-token-html) to a form
                                 anti-forgery/wrap-anti-forgery
                                ; handle exceptions
                                 exception-middleware
                                ; coerce request and response to spec
                                 ring-coercion/coerce-exceptions-middleware
                                 ring-coercion/coerce-request-middleware
                                 ring-coercion/coerce-response-middleware]))}})
      (ring/routes
        (create-resource-handler-cached {:path "/assets/"
                                         :cached? (:cache-assets? options)
                                         :cache-control (:cache-control options)})
        (ring/redirect-trailing-slash-handler)
        (ring/create-default-handler default-handlers)))))

(defn get-handler-ssr
  [handler-config {:keys [options]
                   :as context}]
  (let [handler-fn #(handler-ssr handler-config context)]
    (if (:auto-reload? options)
      (wrap-reload handler-fn)
      (handler-fn))))
