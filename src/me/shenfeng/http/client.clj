(ns me.shenfeng.http.client
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler RespListener RespListener$IFilter]
           (java.net URI URLEncoder)
           me.shenfeng.http.HttpMethod))

(defn- normalize-headers [headers keywordize-headers?]
  (reduce (fn [m [k v]]
            (assoc m (if keywordize-headers? (keyword (str/lower-case k))
                         (str/lower-case k)) v))
          {} headers))

(defonce default-client (atom nil))

(defn init-client "Initializes and returns a new HTTP client."
  [& {:keys [timeout user-agent] :as default-request-opts
      :or {timeout 40000 user-agent "http-kit/1.3"}}]
  (HttpClient. (HttpClientConfig. timeout user-agent)))

(defn get-default-client "Returns default HTTP client, initializing as neccesary."
  [] (if-let [c @default-client] c (reset! default-client (init-client))))

(defn request*
  "Issues an async HTTP request and returns a promise object to which the value
  of `(callback <http-response>)` or `((or error-callback callback) <exception>)`
  will be delivered. See also `request`."
  [{:keys [client url method headers data timeout]
    :or   {method :get client (get-default-client)}}
   callback & [error-callback]]
  (let [uri (URI. url)
        method (case method
                 :get  HttpMethod/GET
                 :post HttpMethod/POST
                 :put  HttpMethod/PUT)
        response (promise)]
    (.exec ^HttpClient client uri method headers data
           (or timeout -1) ; -1 for client default
           (RespListener.
            (reify IResponseHandler
              (onSuccess [this status headers body]
                (deliver response
                         (callback {:body body
                                    :headers (normalize-headers headers true)
                                    :status status})))
              (onThrowable [this t]
                (deliver response
                         (try ((or error-callback callback) (Exception. t))
                              (catch Exception e e)))))))
    response))

(defmacro request
  "Issues an asynchronous HTTP request, binds the HTTP response or exception to
  `resp`, then executes the given handler body in the context of that binding.
  Returns a promise object to which the handler's return value will be delivered:

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

(comment
  @(request {:url "http://www.cnn.com/"}
            {:keys [status body headers] :as resp}
            (if status ; nil on exceptions
              (do (println "Body: " body) body)
              (do (println "Exceptiond: " resp) resp))))

(comment ; TODO

  (defn- url-encode
    "Returns an UTF-8 URL encoded version of the given string."
    [unencoded]
    (URLEncoder/encode unencoded "UTF-8"))

  (defn- generate-query-string
    "Params: {:param1 \"value1\" :params2 \"value2\" :param3 [\"value3\" \"value4\"}

    Return Http Form encoded bytes array. used as HTTP body. Need to set

    Content-Type: application/x-www-form-urlencoded

    in the Request's Headers for server to properly understand it"
    [params]
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
               "UTF-8")))