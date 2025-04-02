(ns reitit-extras.core
  "Useful Reitit router middlewares and helpers."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup2.core :as hiccup]
            [muuntaja.core :as muuntaja-core]
            [reitit.coercion.malli :as coercion-malli]
            [reitit.core :as reitit]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as ring-multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as ring-parameters]
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

(defn- wrap-xss-protection
  ([handler]
   (wrap-xss-protection handler {}))
  ([handler _options]
   (x-headers/wrap-xss-protection handler true nil)))

(defn handler-ssr
  "Return main application handler for server-side rendering."
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
                                ; add call (linkboard.components/csrf-token-html) to a form
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
