(ns org.httpkit.sni-client
  "Provides an SNI-capable SSL configurer and client, Ref. #335.
  In a separate namespace from `org.httpkit.client` so that
  http-kit can retain backwards-compatibility with JVM < 8."
  (:import [java.net URI]
           [javax.net.ssl SNIHostName SSLEngine]))

(defn ssl-configurer
  "SNI-capable SSL configurer.
  May be used as an argument to `org.httpkit.client/make-client`:
    (make-client :ssl-configurer (ssl-configurer))"
  [^SSLEngine ssl-engine ^URI uri]
  (let [host-name  (SNIHostName. (.getHost uri))
        ssl-params (doto (.getSSLParameters ssl-engine)
                     (.setServerNames [host-name]))]
    (doto ssl-engine
      (.setUseClientMode true) ; required for JVM 12/13 but not for JVM 8
      (.setSSLParameters ssl-params))))

(defonce
  ^{:doc "Like `org.httpkit.client/default-client`, but provides SNI support using `ssl-configurer`"}
  default-client
  (delay
    (require '[org.httpkit.client]) ; Lazy require to help users avoid circular deps
    (org.httpkit.client/make-client
      {:ssl-configurer ssl-configurer})))
