(ns org.httpkit.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [org.httpkit.client HttpClientConfig HttpClient
            IResponseHandler RespListener IFilter MaxBodyFilter]
           [org.httpkit HttpMethod PrefixThreadFactory HttpUtils]
           [java.util.concurrent ThreadPoolExecutor LinkedBlockingQueue TimeUnit]
           [java.net URI URLEncoder]
           javax.xml.bind.DatatypeConverter))

;;;; Utils

(defn- utf8-bytes [s] (.getBytes         (str s) "utf8"))
(defn url-encode [s] (URLEncoder/encode (str s) "utf8"))
(defn- base64-encode [bytes] (DatatypeConverter/printBase64Binary bytes))

(defn- basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (base64-encode (utf8-bytes basic-auth)))))

(defn- prepare-request-headers
  [{:keys [headers form-params basic-auth user-agent] :as req}]
  (merge headers
    (when form-params {"Content-Type"  "application/x-www-form-urlencoded"})
    (when basic-auth  {"Authorization" (basic-auth-value basic-auth)})
    (when user-agent  {"User-Agent"    user-agent})))

(defn- prepare-response-headers [headers]
  (reduce (fn [m [k v]] (assoc m (keyword k) v)) {} headers))

(defn- query-string
  "Returns URL-encoded query string for given params map."
  [m]
  (let [param (fn [k v]  (str (url-encode (name k)) "=" (url-encode v)))
        join  (fn [strs] (str/join "&" strs))]
    (join (for [[k v] m] (if (sequential? v)
                           (join (map (partial param k) (or (seq v) [""])))
                           (param k v))))))

(comment (query-string {:k1 "v1" :k2 "v2" :k3 nil :k4 ["v4a" "v4b"] :k5 []}))

(defn- coerce-req
  [{:keys [url method body query-params form-params] :as req}]
  (assoc req
    :url (if query-params
           (if (neg? (.indexOf ^String url (int \?)))
             (str url "?" (query-string query-params))
             (str url "&" (query-string query-params)))
           url)
    :method (HttpMethod/fromKeyword (or method :get))
    :headers  (prepare-request-headers req)
    ;; :body  ring body: null, String, seq, InputStream, File
    :body     (if form-params (query-string form-params) body)))

;; thread pool for executing callbacks, since they may take a long time to execute.
;; protect the IO loop thread: no starvation
(def default-pool (let [max (.availableProcessors (Runtime/getRuntime))
                        queue (LinkedBlockingQueue.)
                        factory (PrefixThreadFactory. "client-worker-")]
                    (ThreadPoolExecutor. 0 max 60 TimeUnit/SECONDS queue factory)))

;;;; Public API

(defn max-body-filter "reject if response's body exceeds size in bytes"
  [size] (MaxBodyFilter. (int size)))

(defn init-client "Initializes and returns a new HTTP client. Timeout: 1 minute, keep-alive: 2 minutes"
   [& {:keys [timeout user-agent keep-alive]
       :or {timeout 60000 user-agent "http-kit/2.0" keep-alive 120000}}]
   (HttpClient. (HttpClientConfig. timeout user-agent keep-alive)))

(defonce default-client (delay (init-client)))

(defn request
  "Issues an async HTTP request and returns a promise object to which the value
  of `(callback {:opts _ :status _ :headers _ :body _})` or
     `(callback {:opts _ :error _})` will be delivered.

  When unspecified, `callback` is the identity

  ;; Asynchronous GET request (returns a promise)
  (request {:url \"http://www.cnn.com\"})

  ;; Asynchronous GET request with callback
  (request {:method \"http://www.cnn.com\" :get}
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

 Request options:
    :url :method :headers :timeout :query-params :form-params
    :client :body :basic-auth :user-agent :filter :worker-pool"
  [{:keys [client timeout filter worker-pool] :as opts
    :or {client @default-client timeout -1 filter IFilter/ACCEPT_ALL worker-pool default-pool}}
   callback]
  (let [{:keys [url method headers body]} (coerce-req opts)
        response (promise)
        deliver-resp #(deliver response ;; deliver the result
                               (try ((or callback identity) %1)
                                    (catch Exception e
                                      ;; dump stacktrace to stderr
                                      (HttpUtils/printError (str method " " url "'s callback") e)
                                      ;; return the error
                                      {:opts opts :error e})))
        handler (reify IResponseHandler
                  (onSuccess [this status headers body]
                    (deliver-resp {:opts    opts
                                   :body    body
                                   :headers (prepare-response-headers headers)
                                   :status  status}))
                  (onThrowable [this t]
                    (deliver-resp {:opts opts :error t})))
        listener (RespListener. handler filter worker-pool)]
    (.exec ^HttpClient client url method headers body timeout listener)
    response))

(defmacro ^:private defreq [method]
  `(defn ~method
     ~(str "Issues an async HTTP " (str/upper-case method) " request. "
           "See `request` for details.")
     ~'{:arglists '([url & [opts callback]] [url & [callback]])}
     ~'[url & [s1 s2]]
     (if (fn? ~'s1)
       (request {:url ~'url :method ~(keyword method)} ~'s1)
       (request (merge ~'s1 {:url ~'url :method ~(keyword method)}) ~'s2))))

(defreq get)
(defreq delete)
(defreq head)
(defreq post)
(defreq put)
(defreq options)
(defreq patch)
