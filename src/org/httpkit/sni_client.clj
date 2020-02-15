(ns org.httpkit.sni-client
  "Provides an SNI-capable SSL configurer and client, Ref. #335.
  In a separate namespace from `org.httpkit.client` so that
  http-kit can retain backwards-compatibility with JVM < 8."
  (:import [java.net URI]
           [javax.net.ssl SNIHostName SSLEngine SSLParameters]))

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

(def ^:private sni? (partial = :sni))
(def ^:private hv?  (partial = :hostname-verification))

(defn ssl-configurer
  "SNI-capable SSL configurer.
   May be used as an argument to `org.httpkit.client/make-client`:
    (make-client :ssl-configurer ssl-configurer)"
  ([engine uri]
   (ssl-configurer engine uri :hostname-verification :sni))
  ([^SSLEngine ssl-engine ^URI uri & opts]
   (let [^SSLParameters ssl-params (.getSSLParameters ssl-engine)
         client-mode? (.getUseClientMode ssl-engine)]
     (when (some hv? opts)
       (.setEndpointIdentificationAlgorithm ssl-params "HTTPS"))
     (when (some sni? opts)
       (.setServerNames ssl-params [(SNIHostName. (.getHost uri))]))
     (doto ssl-engine
       (cond->
         ;; need to be careful with Java8 here:
         ;; java.lang.IllegalArgumentException:
         ;; Cannot change mode after SSL traffic has started
         ;; at sun.security.ssl.SSLEngineImpl.setUseClientMode
         (not client-mode?)
         (.setUseClientMode true))
       (.setSSLParameters ssl-params)))))



(defonce
  ^{:doc "Like `org.httpkit.client/default-client`, but provides SNI support using `ssl-configurer`"}
  default-client
  (delay
    (require '[org.httpkit.client]) ; Lazy require to help users avoid circular deps
    (org.httpkit.client/make-client
      {:ssl-configurer ssl-configurer})))
