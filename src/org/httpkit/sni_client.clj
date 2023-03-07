(ns org.httpkit.sni-client
  "Provides an SNI-capable SSL configurer and client, Ref. #335.

  Needs Java >= 8, hostname verification needs Java >= 11.

  Originally in a separate namespace from `org.httpkit.client` to
  retain backwards-compatibility with Java < 8."

  (:require
   [org.httpkit.client]
   [org.httpkit.utils :as utils])

  (:import
   [java.net URI]
   [javax.net.ssl SNIHostName SSLEngine SSLParameters]))

(defn ssl-configurer
  "SNI-capable SSL configurer.
   May be used as an argument to `org.httpkit.client/make-client`:
    (make-client :ssl-configurer (ssl-configurer))"
  ([ssl-engine uri] (ssl-configurer {} ssl-engine uri))
  ([{:keys [hostname-verification? sni?] :as opts
     :or   {;; TODO Better option/s than hacky version check?
            hostname-verification? (utils/java-version>= 11)
            sni?                   true}}
    ^SSLEngine ssl-engine ^URI uri]

   (let [^SSLParameters ssl-params (.getSSLParameters ssl-engine)]
     (when hostname-verification? (.setEndpointIdentificationAlgorithm ssl-params "HTTPS"))
     (when sni?                   (.setServerNames                     ssl-params
                                    [(SNIHostName. (.getHost uri))]))

     ;; TODO Better option/s than hacky version check?
     (when (and (utils/java-version>= 11) (not (.getUseClientMode ssl-engine)))
       (.setUseClientMode ssl-engine true))

     (doto ssl-engine
       (.setSSLParameters ssl-params)))))

(defonce
  ^{:deprecated "v2.7"
    :doc "Like `org.httpkit.client/default-client`, but provides SNI support using `ssl-configurer`."}
  default-client
  (delay
    (org.httpkit.client/make-client
      {:ssl-configurer ssl-configurer})))
