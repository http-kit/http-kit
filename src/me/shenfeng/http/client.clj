(ns me.shenfeng.http.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler RespListener RespListener$IFilter]
           javax.xml.bind.DatatypeConverter
           (java.net URI URLEncoder)
           me.shenfeng.http.HttpMethod))

;;from clojure/contrib/strint.clj: author Chas Emerick. with minor modification
(defn- silent-read [s]
  (try
    (let [r (-> s java.io.StringReader. java.io.PushbackReader.)]
      [(read r) (slurp r)])
    ;; this indicates an invalid form -- the head of s is just string data
    (catch Exception e )))

(defn- interpolate
  ([s atom?]
     (if-let [[form rest] (silent-read (subs s (if atom? 2 1)))]
       (cons form (interpolate (if atom? (subs rest 1) rest)))
       (cons (subs s 0 2) (interpolate (subs s 2)))))
  ([^String s]
     (if-let [start (->> ["~{" "~("]
                         (map #(.indexOf s ^String %))
                         (remove #(== -1 %))
                         sort
                         first)]
       (let [f (subs s 0 start)
             rst (interpolate (subs s start) (= \{ (.charAt s (inc start))))]
         (if (> (count f) 0)
           (cons f rst)
           rst))
       (if (> (count s) 0) [s] []))))

(defmacro << [string] `(str ~@(interpolate string)))

(defn- normalize-headers [headers keywordize-headers?]
  (reduce (fn [m [k v]]
            (assoc m (if keywordize-headers? (keyword k) ; is lowercased
                         k) v))
          {} headers))

(defn- url-encode [unencoded] (URLEncoder/encode unencoded "UTF-8"))

(defn- utf8-bytes [#^String s] (.getBytes s "UTF-8"))

(defn- base64-encode [bytes] (DatatypeConverter/printBase64Binary bytes))

(defn- basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (base64-encode (utf8-bytes basic-auth)))))

(defn- form-body [params]
  (.getBytes (str/join "&"
                       (mapcat (fn [[k v]]
                                 (if (sequential? v)
                                   (map #(str (url-encode (name %1))
                                              "="
                                              (url-encode (str %2)))
                                        (repeat k) v)
                                   [(str (url-encode (name k))
                                         "="
                                         (url-encode (str v)))]))
                               params))
             "UTF-8"))

(defonce default-client (atom nil))

(defn init-client "Initializes and returns a new HTTP client."
  [& {:keys [timeout user-agent keep-alive]
      :or {timeout 40000 user-agent "http-kit/1.3" keep-alive 120000}}]
  (HttpClient. (HttpClientConfig. timeout user-agent keep-alive)))

(let [lock (Object.)]
  (defn get-default-client "Returns default HTTP client, initializing as neccesary."
    [] (locking lock
         (if-let [c @default-client] c (reset! default-client (init-client))))))

(defn- coerce-req [{:keys [headers client method body
                           form-params basic-auth user-agent]
                    :as req}]
  (merge req  ;; TODO cookie
         {:headers (merge headers
                          (when form-params
                            {"Content-Type" "application/x-www-form-urlencoded"})
                          (when basic-auth
                            {"Authorization" (basic-auth-value basic-auth)})
                          (when user-agent
                            {"User-Agent" user-agent}))}
         {:body (or (when form-params
                      (form-body form-params)) body)}
         {:client (or client (get-default-client))
          :method (or method :get)}))

(defn request
  "Issues an async HTTP request and returns a promise object to which
   <http-response> or <exception> will be delivered
  `(callback <http-response>)` or `((or error-callback callback) <exception>)`
   See also `request`."
  [req & [callback error-callback]]
  (let [{:keys [client url method headers body timeout]} (coerce-req req)
        method (case method
                 :get  HttpMethod/GET
                 :head HttpMethod/HEAD
                 :options HttpMethod/OPTIONS
                 :delete HttpMethod/DELETE
                 :post HttpMethod/POST
                 :put  HttpMethod/PUT)
        response (promise)]
    (.exec ^HttpClient client url method headers body
           (or timeout -1) ; -1 for client default
           (RespListener.
            (reify IResponseHandler
              (onSuccess [this status headers body]
                (let [resp {:body body
                            :request req
                            :headers (normalize-headers headers true)
                            :status status}]
                  (try (when callback (callback resp))
                       (finally
                        (deliver response resp)))))
              (onThrowable [this t]
                (let [resp {:error t :request req}]
                  (try (when-let [cb (or error-callback (:error-handler req) callback)]
                         (cb resp))
                       (finally
                        (deliver response resp))))))))
    response))

(defmacro ^{:private true} gen-method [method]
  (let [key (keyword method)
        m (str method)
        doc (<< "Issues an asynchronous HTTP ~(str/upper-case m) request.
  Returns a promise object to which the HTTP response or exception will be delivered
  Optional callback and error-callback: `(callback <http-response>)` or `((or error-callback callback) <exception>)`

    ;; Asynchronous
    (~{m} url)

    ;; Synchronous
    (let [{:keys [status body headers] :as esp} @(~{m} ...)]) or
    (deref (~{m} ...) timeout-ms timeout-val)

     ;; Issue 2 concurrent ~{m} requests, then wait for results
    (let [resp1 (~{m} ...)
          resp2 (~{m} ...)]
      (println \"resp1's status: \" (:status @resp1))
      (println \"resp2's status: \" (:status @resp2)))")]
    `(defn ~method ~doc
       [~'url & [~'req ~'callback ~'error-callback]]    ;; ~': better names for tootls
       (if (fn? ~'req)                        ; req is callback, shift params
         (request {:method ~key :url ~'url} ~'req ~'callback)
         (request (assoc ~'req :method ~key :url ~'url) ~'callback ~'error-callback)))))

(gen-method get)
(gen-method delete)
(gen-method head)
(gen-method post)
(gen-method put)
(gen-method options)

(comment
  (defn get
    "Issues an asynchronous HTTP GET request. See `request` for more debail."
    [url & [req callback error-callback]]
    (if (fn? req)                        ; req is callback, shift params
      (request {:method :get :url url} req callback)
      (request (assoc req :method :get :url url) callback error-callback))))

(comment
  (defmacro request*
    "Issues an asynchronous HTTP request, binds the HTTP response or exception to
  `resp`, then executes the given handler body in the context of that binding.
  Returns a promise object to which the HTTP response or exception will be delivered:

     ;; Asynchronous
     (request {:url \"http://www.cnn.com/\"}
              {:keys [status body headers] :as resp}
              (if status ; nil on exceptions
                (do (println \"Body: \" body) body)
                (do (println \"Exception: \" resp) resp)))

     ;; Synchronous
     (let [{:keys [status body headers] :as esp} @(request ...)]) or
     (deref (request ...) timeout-ms timeout-val)

     ;; Issue 2 concurrent requests, then wait for results
    (let [resp1 (request ...)
          resp2 (request ...)]
      (println \"resp1's status: \" (:status @resp1))
      (println \"resp2's status: \" (:status @resp2)))

  See lower-level `request*` for options."
    [options resp & handler]
    `(request ~options (fn [~resp] ~@handler)))


  (defmacro ^{:private true} gen-method [method]
    (let [key (keyword method)
          url 'url req 'req resp 'resp forms 'forms]
      `(defmacro ~method
         "Issues an asynchronous HTTP request. See `request` for more debail."
         ([~url]
            `(request {:method ~~key :url ~~url} ~'resp# nil))
         ([~url ~req]
            `(let [~'req# (merge {:method ~~key :url ~~url} ~~req)]
               (request ~'req# ~'resp# nil)))
         ([~url ~req ~resp & ~forms]
            `(let [~'req# (merge {:method ~~key :url ~~url} ~~req)]
               (request ~'req# ~~resp ~@~forms))))))

  (gen-method get)
  (gen-method delete)
  (gen-method head)
  (gen-method post)
  (gen-method put)
  (gen-method options)

  (defmacro get
    "Issues an asynchronous HTTP GET request. See `Request` for more debail."
    ([url]
       `(request {:method :get :url ~url} resp# nil))
    ([url req]
       `(get ~url ~req resp# nil))
    ([url req resp & forms]
       `(let [req# (assoc ~req :method :get :url ~url)]
          (request req#  ~resp ~@forms)))))
