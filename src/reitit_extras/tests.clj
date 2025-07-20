(ns reitit-extras.tests
  (:require [reitit-extras.core :as reitit-extras]
            [ring.middleware.session.cookie :as ring-session-cookie]
            [ring.middleware.session.store :as ring-session-store]
            [ring.util.codec :as codec]))

(def ^:const CSRF-TOKEN-FORM-KEY :__anti-forgery-token)
(def ^:const CSRF-TOKEN-SESSION-KEY :ring.middleware.anti-forgery/anti-forgery-token)
(def ^:const CSRF-TOKEN-HEADER "X-CSRF-Token")

(defn get-server-url
  "Return full url from jetty server object.
  * server - jetty server object
  * env - :host or :container
  :host - localhost
  :container - testcontainers internal host"
  ([server]
   (get-server-url server :host))
  ([server env]
   (let [base-url (case env
                    :host "http://localhost:"
                    :container "http://host.testcontainers.internal:")
         port (.getLocalPort (first (.getConnectors server)))]
     (str base-url port))))

(defn encrypt-session-to-cookie
  "Encrypt session data to a cookie value using the server's session store."
  [session-data secret-key]
  (-> (ring-session-cookie/cookie-store
        {:key (reitit-extras/string->16-byte-array secret-key)})
      (ring-session-store/write-session nil session-data)
      (codec/form-encode)))

(defn session-cookies
  "Convert session data to cookies for a request."
  [session-data secret-key]
  {"ring-session" {:value (encrypt-session-to-cookie session-data secret-key)
                   :path "/"
                   :http-only true
                   :secure true}})

(defn decrypt-session-from-cookie
  "Decrypt session data from a cookie value using the server's session store."
  [session-value secret-key]
  (when session-value
    (let [store (ring-session-cookie/cookie-store
                  {:key (reitit-extras/string->16-byte-array secret-key)})
          decoded-value (codec/form-decode session-value)]
      (ring-session-store/read-session store decoded-value))))
