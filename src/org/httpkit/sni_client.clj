(ns org.httpkit.sni-client
  "Provides an SNI-capable SSL configurer and client, Ref. #335.

  Needs Java >= 8, hostname verification needs Java >= 11.

  Originally in a separate namespace from `org.httpkit.client` to
  retain backwards-compatibility with Java < 8."

  (:require
   [org.httpkit.utils :as utils])

  (:import
   [java.net URI]
   [javax.net.ssl SNIHostName SSLEngine SSLParameters]))

(defn- ip-host? [hostname]
  (boolean
    (or
      (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" hostname)
      (re-matches #"^\[[0-9a-fA-F:]+\]$"                  hostname))))

(comment
  [(ip-host? (.getHost (java.net.URI. "https://192.168.0.1/foo")))
   (ip-host? (.getHost (java.net.URI. "https://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]/foo")))
   (ip-host? (.getHost (java.net.URI. "https://www.google.com")))])

(defn ssl-configurer
  "SNI-capable SSL configurer.
   May be used as an argument to `org.httpkit.client/make-client`:
    (make-client :ssl-configurer (ssl-configurer))"
  ([ssl-engine uri] (ssl-configurer {} ssl-engine uri))
  ([{:keys [sni? hostname-verification?] :as opts
     :or   {hostname-verification? (utils/java-version>= 11)
            sni?                   true}}
    ^SSLEngine ssl-engine ^URI uri]

   (let [^SSLParameters ssl-params (.getSSLParameters ssl-engine)
         hostname (.getHost uri)
         sni?     (and sni? (not (ip-host? hostname)))]

     (.setEndpointIdentificationAlgorithm ssl-params (when sni? "HTTPS"))
     (.setServerNames                     ssl-params (when sni? [(SNIHostName. hostname)]))

     (when (and
             (utils/java-version>= 11)
             (not (.getUseClientMode ssl-engine)))
       (.setUseClientMode ssl-engine true))

     (.setSSLParameters ssl-engine ssl-params)
     ssl-engine)))

(defonce
  ^{:deprecated "v2.7"
    :doc "Deprecated with http-kit v2.7, var retained only for back-compatibility"}
  default-client (delay nil))
