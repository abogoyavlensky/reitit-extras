(ns reitit-extras.tests)

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
