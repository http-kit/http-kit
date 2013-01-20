(ns me.shenfeng.http.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler RespListener RespListener$IFilter]
           javax.xml.bind.DatatypeConverter
           (java.net URI URLEncoder)
           me.shenfeng.http.HttpMethod))

;;;; Utils

(defn- utf8-bytes [s] (.getBytes         (str s) "utf8"))
(defn- url-encode [s] (URLEncoder/encode (str s) "utf8"))
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
           (if (neg? (.indexOf (str url) (int \?)))
             (str url "?" (query-string query-params))
             (str url "&" (query-string query-params)))
           url)

    :method (case (or method :get)
              :get     HttpMethod/GET
              :head    HttpMethod/HEAD
              :options HttpMethod/OPTIONS
              :delete  HttpMethod/DELETE
              :post    HttpMethod/POST
              :put     HttpMethod/PUT)

    :headers  (prepare-request-headers req)
    :body     (if form-params (utf8-bytes (query-string form-params)) body)))

;;;; Public API

(defn init-client "Initializes and returns a new HTTP client."
   [& {:keys [timeout user-agent keep-alive]
       :or {timeout 40000 user-agent "http-kit/2.0" keep-alive 120000}}]
   (HttpClient. (HttpClientConfig. timeout user-agent keep-alive)))

(defonce default-client (delay (init-client)))

(defn request
  "Issues an async HTTP request and returns a promise object to which the value
  of `(callback {:request _ :status _ :headers _ :body _})` or
     `(callback {:request _ :error _})` will be delivered.

  When unspecified, `callback` is the identity fn.

      ;; Asynchronous GET request (returns a promise)
      (request {:url \"http://www.cnn.com\"})

      ;; Asynchronous GET request with callback
      (request {:method \"http://www.cnn.com\" :get}
        (fn [{:keys [request status body headers error] :as resp}]
          (if error
            (do (println \"Error on\" request) error)
            (do (println \"Success on\" request) body))))

      ;; Synchronous requests
      @(request ...) or (deref (request ...) timeout-ms timeout-val)

      ;; Issue 2 concurrent requests, then wait for results
      (let [resp1 (request ...)
            resp2 (request ...)]
        (println \"resp1's status: \" (:status @resp1))
        (println \"resp2's status: \" (:status @resp2)))

  Request options: :client, :timeout, :headers, :body, :query-params,
    :form-params, :basic-auth, :user-agent"
  [{:keys [client timeout] :or {client @default-client} :as opts} callback]
  (let [{:keys [url method headers body] :as req} (coerce-req opts)
        response     (promise)
        try-callback (fn [resp] (try ((or callback identity) resp)
                                    (catch Exception e {:request req
                                                        :error   e})))]
    (.exec ^HttpClient client url method headers body
           (or timeout -1) ; -1 for client default
           (RespListener.
            (reify IResponseHandler
              (onSuccess [this status headers body]
                (deliver response
                         (try-callback {:request req
                                        :body    body
                                        :headers (prepare-response-headers
                                                  headers)
                                        :status  status})))
              (onThrowable [this t]
                (deliver response (try-callback {:request req
                                                 :error   t}))))))
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