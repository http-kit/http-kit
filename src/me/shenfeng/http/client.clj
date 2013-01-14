(ns me.shenfeng.http.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler RespListener RespListener$IFilter]
           javax.xml.bind.DatatypeConverter
           (java.net URI URLEncoder)
           me.shenfeng.http.HttpMethod))

(defn- normalize-headers [headers keywordize-headers?]
  (reduce (fn [m [k v]]
            (assoc m (if keywordize-headers? (keyword (str/lower-case k))
                         (str/lower-case k)) v))
          {} headers))

(defn- url-encode [unencoded] (URLEncoder/encode unencoded "UTF-8"))
(defn- utf8-bytes [#^String s] (.getBytes s "UTF-8"))
(defn- base64-encode [bytes]
  (DatatypeConverter/printBase64Binary bytes))
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

(defn get-default-client "Returns default HTTP client, initializing as neccesary."
  [] (if-let [c @default-client] c (reset! default-client (init-client))))

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

(defn request*
  "Issues an async HTTP request and returns a promise object to which
   <http-response> or <exception> will be delivered
  `(callback <http-response>)` or `((or error-callback callback) <exception>)`
   See also `request`."
  [req callback & [error-callback]]
  (let [{:keys [client url method headers body timeout]} (coerce-req req)
        uri (URI. url)
        method (case method
                 :get  HttpMethod/GET
                 :delete HttpMethod/DELETE
                 :post HttpMethod/POST
                 :put  HttpMethod/PUT)
        response (promise)]
    (.exec ^HttpClient client uri method headers body
           (or timeout -1) ; -1 for client default
           (RespListener.
            (reify IResponseHandler
              (onSuccess [this status headers body]
                (let [resp {:body body
                            :headers (normalize-headers headers true)
                            :status status}]
                  (deliver response resp)
                  (callback resp)))
              (onThrowable [this t]
                (deliver response t)
                ((or error-callback callback) t)))))
    response))

(defmacro request
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
     @(request ...) or (deref (request ...) timeout-ms timeout-val)

  See lower-level `request*` for options."
  [options resp & handler]
  `(request* ~options (fn [~resp] ~@handler)))

(defmacro get
  ([url]
     `(request {:method :get :url ~url} resp# nil))
  ([url req]
     `(get ~url ~req resp# nil))
  ([url req resp & forms]
     `(let [req# (assoc ~req :method :get :url ~url)]
        (request req#  ~resp ~@forms))))

(defmacro post
  ([url]
     `(request {:method :post :url ~url} resp# nil))
  ([url req]
     `(post ~url ~req resp# nil))
  ([url req resp & forms]
     `(let [req# (merge {:method :post :url ~url} ~req)]
        (request req# ~resp ~@forms))))
