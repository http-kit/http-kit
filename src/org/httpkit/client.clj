(ns org.httpkit.client
  (:refer-clojure :exclude [get proxy])
  (:require [clojure.string :as str]
            [org.httpkit.encode :refer [base64-encode]])
  (:use [clojure.walk :only [prewalk]])
  (:import [org.httpkit.client HttpClient HttpClient$AddressFinder HttpClient$SSLEngineURIConfigurer
            IResponseHandler RespListener IFilter RequestConfig]
           [org.httpkit.logger ContextLogger EventLogger EventNames]
           [org.httpkit HttpMethod PrefixThreadFactory HttpUtils]
           [java.util.concurrent ThreadPoolExecutor LinkedBlockingQueue TimeUnit]
           [java.net URI URLEncoder]
           [org.httpkit.client SslContextFactory MultipartEntity]))

;;;; Utils

(defn- utf8-bytes    [s]     (.getBytes         (str s) "utf8"))
(defn url-encode     [s]     (URLEncoder/encode (str s) "utf8"))

(defn- basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (base64-encode (utf8-bytes basic-auth)))))

(defn- prepare-request-headers
  [{:keys [headers form-params basic-auth oauth-token user-agent] :as req}]
  (merge headers
         (when form-params {"Content-Type"  "application/x-www-form-urlencoded"})
         (when basic-auth  {"Authorization" (basic-auth-value basic-auth)})
         (when oauth-token {"Authorization" (str "Bearer " oauth-token)})
         (when user-agent  {"User-Agent"    user-agent})))

(defn- prepare-response-headers [headers]
  (reduce (fn [m [k v]] (assoc m (keyword k) v)) {} headers))

;;; {:a {:b 1 :c [1 2 3]}} => {"a[b]" 1, "a[c]" [1 2 3]}
(defn- nested-param [params]            ; code copyed from clj-http
  (prewalk (fn [d]
             (if (and (vector? d) (map? (second d)))
               (let [[fk m] d]
                 (reduce (fn [m [sk v]]
                           (assoc m (str (name fk) \[ (name sk) \]) v))
                         {} m))
               d))
           params))

(defn- query-string
  "Returns URL-encoded query string for given params map."
  [m]
  (let [m (nested-param m)
        param (fn [k v]  (str (url-encode (name k)) "=" (url-encode v)))
        join  (fn [strs] (str/join "&" strs))]
    (join (for [[k v] m] (if (sequential? v)
                           (join (map (partial param k) (or (seq v) [""])))
                           (param k v))))))

(comment (query-string {:k1 "v1" :k2 "v2" :k3 nil :k4 ["v4a" "v4b"] :k5 []}))

(defn- coerce-req
  [{:keys [url method body insecure? query-params form-params multipart] :as req}]
  (let [r (assoc req
                 :url (if query-params
                        (if (neg? (.indexOf ^String url (int \?)))
                          (str url "?" (query-string query-params))
                          (str url "&" (query-string query-params)))
                        url)
                 :sslengine (or (:sslengine req)
                                (when (:insecure? req) (SslContextFactory/trustAnybody)))
                 :method    (HttpMethod/fromKeyword (or method :get))
                 :headers   (prepare-request-headers req)
            ;; :body ring body: null, String, seq, InputStream, File, ByteBuffer
                 :body      (if form-params (query-string form-params) body))]
    (if multipart
      (let [entities (into (map (fn [{:keys [name content filename content-type]}]
                                  (MultipartEntity. name content filename content-type)) multipart)
                           (map (fn [[k v]] (MultipartEntity. k v nil nil)) form-params))
            boundary (MultipartEntity/genBoundary entities)]
        (-> r
            (assoc-in [:headers "Content-Type"]
                      (str "multipart/form-data; boundary=" boundary))
            (assoc :body (MultipartEntity/encode boundary entities))))
      r)))

;; thread pool for executing callbacks, since they may take a long time to execute.
;; protect the IO loop thread: no starvation
(def default-pool (let [max (.availableProcessors (Runtime/getRuntime))
                        queue (LinkedBlockingQueue.)
                        factory (PrefixThreadFactory. "client-worker-")]
                    (ThreadPoolExecutor. max max 60 TimeUnit/SECONDS queue factory)))

;;;; Public API

(defn max-body-filter "reject if response's body exceeds size in bytes"
  [size] (org.httpkit.client.IFilter$MaxBodyFilter. (int size)))

;;; "Get the default client. Normally, you only need one client per application. You can config parameter per request basic"
(defonce default-client (delay (HttpClient.)))

(defn make-client
  "Returns an HttpClient with specified options:
    :max-connections    ; Max connection count, default is unlimited (-1)
    :address-finder     ; (fn [java.net.URI]) -> java.net.InetSocketAddress
    :ssl-configurer     ; (fn [javax.net.ssl.SSLEngine java.net.URI])
    :error-logger       ; (fn [text ex])
    :event-logger       ; (fn [event-name])
    :event-names        ; {<http-kit-event-name> <loggable-event-name>}"
  [{:keys [max-connections
           address-finder
           ssl-configurer
           error-logger
           event-logger
           event-names]}]
  (HttpClient.
    (or max-connections -1)
    (if address-finder
      (reify HttpClient$AddressFinder
        (findAddress [this uri] (address-finder uri)))
      HttpClient$AddressFinder/DEFAULT)
    (if ssl-configurer
      (reify HttpClient$SSLEngineURIConfigurer
        (configure [this ssl-engine uri] (ssl-configurer ssl-engine uri)))
      HttpClient$SSLEngineURIConfigurer/NOP)
    (if error-logger
      (reify ContextLogger
        (log [this message error] (error-logger message error)))
      ContextLogger/ERROR_PRINTER)
    (if event-logger
      (reify EventLogger
        (log [this event] (event-logger event)))
      EventLogger/NOP)
    (cond
      (nil? event-names) EventNames/DEFAULT
      (map? event-names) (EventNames. event-names)
      (instance? EventNames
        event-names)     event-names
      :otherwise         (throw (IllegalArgumentException.
                                  (format "Invalid event-names: (%s) %s"
                                    (class event-names) (pr-str event-names)))))))

(def ^:dynamic ^:private *in-callback* false)

(defn ^:private deadlock-guard [response]
  (let [e #(Exception. "http-kit client deadlock-guard: refusing to deref a request callback from inside a callback. This feature can be disabled with the request's `:deadlock-guard?` option.")]
    (reify
      clojure.lang.IPending
      (isRealized [_] (realized? response))
      clojure.lang.IDeref
      (deref [_] (if *in-callback* (throw (e)) (deref response)))
      clojure.lang.IBlockingDeref
      (deref [_ ms value] (if *in-callback* (throw (e)) (deref response ms value))))))

(defn request
  "Issues an async HTTP request and returns a promise object to which the value
  of `(callback {:opts _ :status _ :headers _ :body _})` or
     `(callback {:opts _ :error _})` will be delivered.
  The latter will be delivered on client errors only, not on http errors which will be
  contained in the :status of the first.

  When unspecified, `callback` is the identity

  ;; Asynchronous GET request (returns a promise)
  (request {:url \"http://www.cnn.com\"})

  ;; Asynchronous GET request with callback
  (request {:url \"http://www.cnn.com\" :method :get}
    (fn [{:keys [opts status body headers error] :as resp}]
      (if error
        (println \"Error on\" opts)
        (println \"Success on\" opts))))

  ;; Synchronous requests
  @(request ...) or (deref (request ...) timeout-ms timeout-val)

  ;; Issue 2 concurrent requests, then wait for results
  (let [resp1 (request ...)
        resp2 (request ...)]
    (println \"resp1's status: \" (:status @resp1))
    (println \"resp2's status: \" (:status @resp2)))

  Output coercion:
  ;; Return the body as a byte stream
  (request {:url \"http://site.com/favicon.ico\" :as :stream})
  ;; Coerce as a byte-array
  (request {:url \"http://site.com/favicon.ico\" :as :byte-array})
  ;; return the body as a string body
  (request {:url \"http://site.com/string.txt\" :as :text})
  ;; Try to automatically coerce the output based on the content-type header, currently supports :text :stream, (with automatic charset detection)
  (request {:url \"http://site.com/string.txt\" :as :auto})

  Request options:
    :url :method :headers :timeout :connect-timeout :idle-timeout :query-params
    :as :form-params :client :body :basic-auth :user-agent :filter :worker-pool"
  [{:keys [client timeout connect-timeout idle-timeout filter worker-pool keepalive as follow-redirects
           max-redirects response trace-redirects allow-unsafe-redirect-methods proxy-host proxy-port
           proxy-url tunnel? deadlock-guard?]
    :as opts
    :or {connect-timeout 60000
         idle-timeout 60000
         follow-redirects true
         max-redirects 10
         filter IFilter/ACCEPT_ALL
         worker-pool default-pool
         response (promise)
         keepalive 120000
         as :auto
         tunnel? false
         deadlock-guard? true
         proxy-host nil
         proxy-port -1
         proxy-url nil}}
   & [callback]]
  (let [client (or client @default-client)
        {:keys [url method headers body sslengine]} (coerce-req opts)
        deliver-resp #(deliver response ;; deliver the result
                               (try
                                 (binding [*in-callback* true]
                                   ((or callback identity) %1))
                                 (catch Throwable e
                                   ;; dump stacktrace to stderr
                                   (HttpUtils/printError (str method " " url "'s callback") e)
                                   ;; return the error
                                   {:opts opts :error e})))
        handler (reify IResponseHandler
                  (onSuccess [this status headers body]
                    (if (and follow-redirects
                             (#{301 302 303 307 308} status)) ; should follow redirect
                      (if (>= max-redirects (count trace-redirects))
                        (let [location (str (.resolve (URI. url) ^String (.get headers "location")))
                              change-to-get (and (not allow-unsafe-redirect-methods)
                                                 (#{301 302 303} status))]
                          (request (assoc opts ; follow 301 and 302 redirect
                                     :url location
                                     :response response
                                     :query-params (if change-to-get nil (:query-params opts))
                                     :form-params (if change-to-get nil (:form-params opts))
                                     :method (if change-to-get
                                               :get ;; change to :GET
                                               (:method opts))  ;; do not change
                                     :trace-redirects (conj trace-redirects url))
                                   callback))
                        (deliver-resp {:opts (dissoc opts :response)
                                       :error (Exception. (str "too many redirects: "
                                                               (count trace-redirects)))}))
                      (deliver-resp {:opts    (dissoc opts :response)
                                     :body    body
                                     :headers (prepare-response-headers headers)
                                     :status  status})))
                  (onThrowable [this t]
                    (deliver-resp {:opts opts :error t})))
        listener (RespListener. handler filter worker-pool
                                ;; only the 4 support now
                                (case as :auto 1 :text 2 :stream 3 :byte-array 4))
        effective-proxy-url (if proxy-host (str proxy-host ":" proxy-port) proxy-url)
        connect-timeout (or timeout connect-timeout)
        idle-timeout    (or timeout idle-timeout)
        cfg (RequestConfig. method headers body connect-timeout idle-timeout
              keepalive effective-proxy-url tunnel?)]
    (.exec ^HttpClient client url cfg sslengine listener)
    (if deadlock-guard?
      (deadlock-guard response)
      response)))

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
