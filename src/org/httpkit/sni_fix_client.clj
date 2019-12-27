(ns org.httpkit.sni-fix-client
  "This namespace provides a make-client function that will build a client
  with a pre-configured :sni-configurer key as a workaround for the issue
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

(defn make-client
  "Make a cliente with pre-configured :sni-configurer section"
  []
  (http-client/make-client {:ssl-configurer ssl-configurer}))

(comment

  (def client (http-client/make-client))

  @(http-client/request {:method :get
                         :url "https://www.google.com/"
                         :client client})
  )
