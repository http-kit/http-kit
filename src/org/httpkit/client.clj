(ns org.httpkit.client
  (:refer-clojure :exclude [get proxy])
  (:require
   [clojure.string :as str]
   [clojure.walk   :as walk]
   [org.httpkit.encode :refer [base64-encode]]
   [org.httpkit.utils :as utils])

  (:import
   [org.httpkit.client HttpClient HttpClient$AddressFinder HttpClient$ChannelFactory HttpClient$SSLEngineURIConfigurer IResponseHandler RespListener IFilter RequestConfig]
   [org.httpkit.logger ContextLogger EventLogger EventNames]
   [org.httpkit HttpMethod PrefixThreadFactory HttpUtils]
   [java.util.concurrent ThreadPoolExecutor LinkedBlockingQueue TimeUnit]
   [java.net URI URLEncoder]
   [org.httpkit.client ClientSslEngineFactory MultipartEntity]
   [javax.net.ssl SSLContext SSLEngine]))

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
  (cond-> headers
    form-params (assoc "Content-Type"  "application/x-www-form-urlencoded")
    basic-auth  (assoc "Authorization" (basic-auth-value basic-auth))
    oauth-token (assoc "Authorization" (str "Bearer " oauth-token))
    user-agent  (assoc "User-Agent"    user-agent)))

(defn- prepare-response-headers [headers]
  (reduce (fn [m [k v]] (assoc m (keyword k) v)) {} headers))

(defn- name* [v] (if (instance? clojure.lang.Named v) (name v) v))

(defn- nested-param
  "{:a {:b 1 :c [1 2 3]}} => {\"a[b]\" 1, \"a[c]\" [1 2 3]}, etc."
  [params style]
  ;; Code copied from clj-http
  (walk/prewalk
    (fn [d]
      (if (and (vector? d) (or (map? (second d))
                               (and (= style :indexed) (vector? (second d)))))
        (let [[fk m] d]
          (reduce-kv (fn [m sk v]
                       (assoc m (str (name* fk) \[ (name* sk) \]) v))
                     {} m))
        d))
    params))

(defn- param-encoder [sfx]
  (fn [k v] (str (url-encode (str (name k) sfx)) "=" (url-encode v))))

(defn query-string
  "Returns URL-encoded query string for given params map."
  [m style]
  (let [m (nested-param m style)
        param (param-encoder "")
        param-arr (if (= style :array) (param-encoder "[]") param)
        join (fn [strs] (str/join "&" strs))]
    (join (for [[k v] m] (if (sequential? v)
                           (if (= style :comma-separated)
                             (param k (str/join "," v))
                             (join (map (partial param-arr k) (or (seq v) [""]))))
                           (param k v))))))

(comment
  (query-string {:k1 "v1" :k2 "v2" :k3 nil :k4 ["v4a" "v4b"] :k5 []} nil)
  (query-string {:k1 "v1" :k2 "v2" :k3 nil :k4 ["v4a" "v4b"] :k5 []} :comma-separated)
  (query-string {:k1 "v1" :k2 "v2" :k3 nil :k4 ["v4a" "v4b"] :k5 []} :array)
  (with-redefs [url-encode identity]
    (query-string {:card {:numbers [4242 1313 6767] :exp_month 12}
                   :prices [{:amt 12 :name "prod-1"}
                            {:amt 20 :name "prod-2"}]} :index)))

(defn- coerce-req
  [{:keys [url method body query-params form-params multipart multipart-mixed? nested-param-style] :as req}]
  (let [r (assoc req
                 :url (if query-params
                        (if (neg? (.indexOf ^String url (int \?)))
                          (str url "?" (query-string query-params nested-param-style))
                          (str url "&" (query-string query-params nested-param-style)))
                        url)
                 :method    (HttpMethod/fromKeyword (or method :get))
                 :headers   (prepare-request-headers req)
            ;; :body ring body: null, String, seq, InputStream, File, ByteBuffer
                 :body      (if form-params (query-string form-params nested-param-style) body))]
    (if multipart
      (let [entities (into (map (fn [{:keys [name content filename content-type]}]
                                  (MultipartEntity. name content filename content-type)) multipart)
                           (map (fn [[k v]] (MultipartEntity. k v nil nil)) form-params))
            boundary (MultipartEntity/genBoundary entities)]
        (-> r
            (assoc-in [:headers "Content-Type"]
                      (str "multipart/" (if multipart-mixed? "mixed" "form-data")"; boundary=" boundary))
            (assoc :body (MultipartEntity/encode boundary entities multipart-mixed?))))
      r)))

(defn new-worker
  "Returns {:keys [n-cores type pool ...]} where `:pool` is a new
  `java.util.concurrent.ExecutorService` for handling client callbacks.

  When on JVM 21+, uses `newVirtualThreadPerTaskExecutor` by default.
  Otherwise creates a standard `ThreadPoolExecutor` with default min and max
  thread count auto-selected based on currently available processor count."

  [{:keys [queue-size n-min-threads n-max-threads prefix allow-virtual?] :as opts}]
  (utils/new-worker
    {:default-prefix "http-kit-client-worker-"
     :default-queue-type :linked
     :default-queue-size nil
     :n-min-threads-factor 1.0 ; => 8  threads on 8 core system, etc.
     :n-max-threads-factor 2.0 ; => 16 threads on 8 core system, etc.
     :keep-alive-msecs 60000}
    opts))

(comment (new-worker {}))

(defonce
  ^{:doc
  "(delay (new-worker {})), used to handle client callbacks.
  See `new-worker` for details."}
  default-worker_ (delay (new-worker {})))

;;;; Public API

(defn max-body-filter "reject if response's body exceeds size in bytes"
  [size] (org.httpkit.client.IFilter$MaxBodyFilter. (int size)))

(defn make-ssl-engine
  "Returns an SSLEngine using default or given SSLContext."
  (^SSLEngine [               ] (make-ssl-engine (SSLContext/getDefault)))
  (^SSLEngine [^SSLContext ctx] (.createSSLEngine ctx)))

(defn make-client
  "Returns an HttpClient with specified options:
    :max-connections    ; Max connection count, default is unlimited (-1)
    :address-finder     ; (fn [java.net.URI]) -> `java.net.SocketAddress`
    :channel-factory    ; (fn [java.net.SocketAddress]) -> `java.nio.channels.SocketChannel`
    :ssl-configurer     ; (fn [javax.net.ssl.SSLEngine java.net.URI])
    :error-logger       ; (fn [text ex])
    :event-logger       ; (fn [event-name])
    :event-names        ; {<http-kit-event-name> <loggable-event-name>}
    :bind-address       ; when present will pass local address to SocketChannel.bind()"
  [{:keys [max-connections
           address-finder
           ssl-configurer
           error-logger
           event-logger
           event-names
           bind-address
           channel-factory]}]
  (HttpClient.
   (or max-connections -1)

   ^HttpClient$AddressFinder
   (if address-finder
     (reify HttpClient$AddressFinder (findAddress [this uri] (address-finder uri)))
     (do    HttpClient$AddressFinder/DEFAULT))

   ^HttpClient$ChannelFactory
   (if channel-factory
     (reify HttpClient$ChannelFactory (createChannel [this address] (channel-factory address)))
     (do    HttpClient$ChannelFactory/DEFAULT))

   ^HttpClient$SSLEngineURIConfigurer
   (if ssl-configurer
     (reify HttpClient$SSLEngineURIConfigurer (configure [this ssl-engine uri] (ssl-configurer ssl-engine uri)))
     (do    HttpClient$SSLEngineURIConfigurer/NOP))

   ^ContextLogger
   (if error-logger
     (reify ContextLogger (log [this message error] (error-logger message error)))
     (do    ContextLogger/ERROR_PRINTER))

   ^EventLogger
   (if event-logger
     (reify EventLogger (log [this event] (event-logger event)))
     (do    EventLogger/NOP))

   ^EventNames
   (cond
     (nil?                 event-names)  EventNames/DEFAULT
     (map?                 event-names) (EventNames. event-names)
     (instance? EventNames event-names)              event-names
     :else
     (throw
       (IllegalArgumentException.
         (format "Invalid event-names: (%s) %s"
           (class event-names) (pr-str event-names)))))

   bind-address))

(def ^:private ssl-configurer
  "SNI-capable SSL configurer, or nil."
  (when (utils/java-version>= 8)
    ;; Note "Gilardi scenario"
    (require            'org.httpkit.sni-client)
    (some-> (ns-resolve 'org.httpkit.sni-client 'ssl-configurer) deref)))

;; Normally only need one client per application - params can be configured per req
(defonce  legacy-client (delay (HttpClient.)))
(defonce default-client
  (delay
    (if ssl-configurer
      (make-client {:ssl-configurer ssl-configurer})
      @legacy-client)))

(defonce
  ^{:dynamic true
    :doc "Specifies the default `HttpClient` used by the `request` function.
Value may be a delay. See also `make-client`."}
  *default-client* default-client)

(def ^:dynamic ^:private *in-callback* false)

(defn ^:private deadlock-guard [response]
  (let [e #(Exception. "http-kit client deadlock-guard: refusing to deref a request callback from inside a callback. This feature can be disabled with the request's `:deadlock-guard?` option.")]
    (reify
      clojure.lang.IPending       (isRealized [_] (realized? response))
      clojure.lang.IDeref         (deref [_         ] (if *in-callback* (throw (e)) (deref response)))
      clojure.lang.IBlockingDeref (deref [_ ms value] (if *in-callback* (throw (e)) (deref response ms value))))))

(defn request
  "Issues an async HTTP request and returns a promise object to which the value
  of `(callback {:opts _ :status _ :headers _ :body _})` or
     `(callback {:opts _ :error _})` will be delivered.
  The latter will be delivered on client errors only, not on http errors which will be
  contained in the :status of the first.

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

  Returned body type is controlled by `:as` option:

    Without automatic unzipping:
      `:none`           - org.httpkit.DynamicBytes
      `:raw-byte-array` - bytes[]

    With automatic unzipping:
      `:byte-array`     - bytes[]
      `:stream`         - ByteInputStream
      `:text`           - String (charset based on Content-Type header)
      `:auto`           - As `:text` or `:stream` (based on Content-Type header)

  Request options:
    :url :method :headers :timeout :connect-timeout :idle-timeout :query-params
    :as :form-params :client :body :basic-auth :user-agent :filter :worker-pool"

  [{:keys [client timeout connect-timeout idle-timeout filter worker-pool keepalive as follow-redirects
           max-redirects response trace-redirects allow-unsafe-redirect-methods proxy-host proxy-port
           proxy-url tunnel? deadlock-guard? auto-compression? insecure?]
    :as opts
    :or
    {connect-timeout 60000
     idle-timeout 60000
     follow-redirects true
     max-redirects 10
     filter IFilter/ACCEPT_ALL
     response (promise)
     keepalive 120000
     as :auto
     tunnel? false
     deadlock-guard? true
     proxy-host nil
     proxy-port -1
     proxy-url nil
     auto-compression? true}}

   & [callback]]

  (let [{:keys [url method headers body]} (coerce-req opts)
        [sslengine client]
        (if insecure?
          [(ClientSslEngineFactory/trustAnybody) @legacy-client]
          [(:sslengine opts)
           (or
             (force          client)
             (force *default-client*)
             (force  default-client))])

        deliver-resp
        (fn [resp]
          (deliver response
            (if callback
              (try
                (binding [*in-callback* true] (callback resp))
                (catch Throwable t
                  (HttpUtils/printError (str method " " url "'s callback") t)
                  {:opts opts :error t}))
              resp)))

        handler
        (reify IResponseHandler
          (onThrowable [this t] (deliver-resp {:opts opts :error t}))
          (onSuccess [this status headers body]
            (if-let [follow-redirect? (and follow-redirects (#{301 302 303 307 308} status))]

              ;; Follow redirect
              (if (>= max-redirects (count trace-redirects))
                (if-let [^String location-header (.get headers "location")]

                  (let [redirect-location (str (.resolve (URI. url) location-header))
                        change-to-get? (and (not allow-unsafe-redirect-methods) (#{301 302 303} status))]

                    (request
                      (assoc opts
                        :client          client ; Retain current dynamic client, Ref. #464
                        :url             redirect-location
                        :response        response
                        :query-params    (if change-to-get?  nil (:query-params opts))
                        :form-params     (if change-to-get?  nil (:form-params  opts))
                        :method          (if change-to-get? :get (:method       opts))
                        :trace-redirects (conj trace-redirects url))
                      callback))

                  (deliver-resp
                    {:opts  (dissoc opts :response)
                     :error (Exception. (str "No location header is present on redirect response"))}))

                (deliver-resp
                  {:opts  (dissoc opts :response)
                   :error (Exception. (str "too many redirects: " (count trace-redirects)))}))

              ;; Don't follow redirect
              (deliver-resp
                {:opts    (dissoc opts :response)
                 :body    body
                 :headers (prepare-response-headers headers)
                 :status  status}))))

        worker-pool (or (force worker-pool) (:pool @default-worker_))

        listener
        (RespListener. handler filter worker-pool
          ;; 0 will return as DynamicBytes and 5 returns bytes[] - i.e. you will need to handle unzip yourself
          ;; otherwise, there are 5 coercions supported for now
          (case as :none 0 :auto 1 :text 2 :stream 3 :byte-array 4 :raw-byte-array 5))

        effective-proxy-url (if proxy-host (str proxy-host ":" proxy-port) proxy-url)
        connect-timeout (or timeout connect-timeout)
        idle-timeout    (or timeout idle-timeout)
        cfg (RequestConfig. method headers body connect-timeout idle-timeout
              keepalive effective-proxy-url tunnel? auto-compression?)]

    (.exec ^HttpClient client url cfg sslengine listener)

    (if deadlock-guard?
      (deadlock-guard response)
      (do             response))))

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
