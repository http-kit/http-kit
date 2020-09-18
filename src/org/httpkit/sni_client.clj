(ns org.httpkit.sni-client
  "Provides an SNI-capable SSL configurer and client, Ref. #335.
  In a separate namespace from `org.httpkit.client` so that
  http-kit can retain backwards-compatibility with JVM < 8."
  (:require [org.httpkit.client])
  (:import
   [java.net URI]
   [javax.net.ssl SNIHostName SSLEngine SSLParameters]))

(defn- parse-java-version
  "Ref. https://stackoverflow.com/a/2591122"
  [^String s]
  (if (.startsWith s "1.") ; e.g. "1.6.0_23"
    (Integer/parseInt (.substring s 2 3))
    (let [dot-idx (.indexOf s ".")] ; e.g. "9.0.1"
      (when (not= dot-idx -1)
        (Integer/parseInt (.substring s 0 dot-idx))))))

(comment
  (parse-java-version "1.6.0_23") ; 6
  (parse-java-version "9.0.1")    ; 9
  )

(def ^:private java-version_
  (delay (parse-java-version (str (System/getProperty "java.version")))))

(comment @java-version_)

(defn ssl-configurer
  "SNI-capable SSL configurer.
   May be used as an argument to `org.httpkit.client/make-client`:
    (make-client :ssl-configurer (ssl-configurer))"
  ([ssl-engine uri] (ssl-configurer {} ssl-engine uri))
  ([{:keys [hostname-verification? sni?] :as opts
     :or   {;; TODO Better option/s than hacky version check?
            hostname-verification? (>= @java-version_ 11)
            sni?                   true}}
    ^SSLEngine ssl-engine ^URI uri]

   (let [^SSLParameters ssl-params (.getSSLParameters ssl-engine)]
     (when hostname-verification? (.setEndpointIdentificationAlgorithm ssl-params "HTTPS"))
     (when sni?                   (.setServerNames                     ssl-params
                                    [(SNIHostName. (.getHost uri))]))

     ;; TODO Better option/s than hacky version check?
     (when (and (>= @java-version_ 11) (not (.getUseClientMode ssl-engine)))
       (.setUseClientMode ssl-engine true))

     (doto ssl-engine
       (.setSSLParameters ssl-params)))))

(defonce
  ^{:doc "Like `org.httpkit.client/default-client`, but provides SNI support using `ssl-configurer`. NB Hostname verification currently requires Java version >= 11."}
  default-client
  (delay
    (org.httpkit.client/make-client
      {:ssl-configurer ssl-configurer})))
