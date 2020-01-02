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

;;; "Get the default client. Normally, you only need one client per application. You can config parameter per request basic"
(defonce default-sni-fixed-client (delay (HttpClient.)))

(comment

  (def client (http-client/make-client))

  @(http-client/request {:method :get
                         :url "https://www.google.com/"
                         :client client})
  )

(defn request
  "Wraps http-kit.client/request to provide a sni-fixed client
  if a client key is not provided by the caller"
  [options & [callback]]
  ;; client defaults to default-sni-fixed-client if not specified
  (let [client (get options :client @default-sni-fixed-client)]
    (http-client/request (assoc options :client client) callback)))

;; replicate http-kit.client macro using the request function wrapper
(defmacro ^:private defreq [method]
  `(defn ~method
     ~(str "Issues an async HTTP " (str/upper-case method) " request. "
           "See `request` for details.")
     ~'{:arglists '([url & [opts callback]] [url & [callback]])}
     ~'[url & [s1 s2]]
     (if (or (instance? clojure.lang.MultiFn ~'s1) (fn? ~'s1) (keyword? ~'s1))
       (request {:url ~'url :method ~(keyword method)} ~'s1)
       (request (merge ~'s1 {:url ~'url :method ~(keyword method)}) ~'s2))))

(defreq get)
(defreq delete)
(defreq head)
(defreq post)
(defreq put)
(defreq options)
(defreq patch)
(defreq propfind)
(defreq proppatch)
(defreq lock)
(defreq unlock)
(defreq report)
(defreq acl)
(defreq copy)
(defreq move)
