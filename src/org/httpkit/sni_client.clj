(ns org.httpkit.sni-client
  "This namespace provides a default client with a pre-configured
  :sni-configurer key as a workaround for the issue
  https://github.com/http-kit/http-kit/issues/393

  direct link to comment with proposed workaround
  https://github.com/http-kit/http-kit/issues/393#issuecomment-563820823"
  (:require [org.httpkit.client :as http-client])
  (:import java.net.URI
           [javax.net.ssl SNIHostName SSLEngine]))

(defn ssl-configurer [^SSLEngine eng, ^URI uri]
  (let [host-name (SNIHostName. (.getHost uri))
        params (doto (.getSSLParameters eng)
                 (.setServerNames [host-name]))]
    (doto eng
      (.setUseClientMode true) ;; required for JDK12/13 but not for JDK1.8
      (.setSSLParameters params))))

(defonce
  ^{:doc "Client with a default :sni-configurer option; it can be
used directly, as a parameter to `org.http-kit.client/request` call,
set as a default for the whole application by using `alter-root-var`
or set as a per-thread default by dynamicly rebinding
`org.http-kit.client/*default-client`; examples code provided in the
following comment section."}
  default-sni-client
  (delay (http-client/make-client {:ssl-configurer ssl-configurer})))

(comment
  ;; redifine the default client for the whole application
  (alter-var-root #'org.http-kit.client/*default-client* default-sni-client)
  ;; this call to get will use default-sni-client client
  (org.http-kit.client/get "https://example.com")

  ;; specify a custom client for a particular context
  (binding [org.httpkit.client/*default-client* default-sni-client]
    (org.httpkit.client/get "https://example.com"))
  )
