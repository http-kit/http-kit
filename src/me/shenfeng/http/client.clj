(ns me.shenfeng.http.client
  (:require [clojure.string :as str])
  ;; TODO Remove HttpClientConfig import?
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler TextRespListener RespListener$IFilter RespListener]
           [java.net Proxy Proxy$Type URI]
           me.shenfeng.http.HttpMethod))

(defn- normalize-headers [headers keywordize-headers?]
  (reduce (fn [m [k v]]
            (assoc m (if keywordize-headers? (keyword (str/lower-case k))
                         (str/lower-case k)) v))
          {} headers))

(defonce default-client (atom nil))

(defn init-client "Initializes and returns a new HTTP client."
  ;; TODO HttpClientConfig deprecated in favour of per-request config?
  [] (HttpClient. (HttpClientConfig. 40000 "http-kit/1.3")))

(defn get-default-client "Returns default HTTP client, initializing as neccesary."
  [] (if-let [c @default-client] c (reset! default-client (init-client))))

(defn request*
  "Low-level asynchronous HTTP request fn. Returns a promise object to which
  the HTTP response (or exception) will be asynchronously delivered. In most
  cases you'll want to use the higher-level `request` macro."
  [{:keys [client url method headers body timeout user-agent]
    :or   {method :get timeout 40000 user-agent "http-kit/1.3"
           client (get-default-client)}}]
  (let [uri (URI. url)
        method (case method
                 :get  HttpMethod/GET
                 :post HttpMethod/POST
                 :put  HttpMethod/PUT)
        response (promise)]
    (.exec ^HttpClient client uri method headers ; TODO :timeout, :user-agent
           body
           Proxy/NO_PROXY ; TODO Remove?
           (RespListener.
            (reify IResponseHandler
              (onSuccess [this status headers body]
                (deliver response
                         {:body body
                          :headers (normalize-headers headers true)
                          :status status}))
              (onThrowable [this t]
                (deliver response (Exception. t))))))
    response))

(comment (request* {:url "http://www.cnn.com/"}))

;;; (request req response & forms)
;;; this is a low level interface, wrap with middleware for high level functionality
(defmacro request [{:keys [client url method headers body timeout user-agent]
                    :or   {method :get timeout 40000 user-agent "http-kit/1.3"
                           client (get-default-client)}}
                   resp & handler-forms]
  `(let [uri# (URI. ~url)
         method# (case ~method
                   :get HttpMethod/GET
                   :post HttpMethod/POST
                   :put HttpMethod/PUT)]
     (init-if-needed)
     (.exec ^HttpClient @client uri# method# ~headers
            ~body
            Proxy/NO_PROXY ; TODO proxy
            (RespListener.
             (reify IResponseHandler
               (onSuccess [this# status# headers# body#]
                 (let [~resp {:body body#
                              :headers (normalize-headers headers# true)
                              :status status#}]
                   ;; TODO executed in http-client's loop thread?
                   ~@handler-forms))
               (onThrowable [this# t#]
                 ;; TODO executed in http-client's loop thread?
                 (throw (Exception. t#))))))))

(comment
  ;; Usage of request
  (try
    (request {:url "http://127.0.0.1:8000"} resp
             (println resp))
    (request {:url "http://127.0.0.1:8000"} {:keys [status body headers]}
             (println status))
    (catch Exception e                    ; request error
      (println e)))

  ;; Interesting if possible
  ;; TODO, how to write the macro?
  (defmacro async [& body]
    )
  (async
   ;; this is what clj-http use, just wrap with async, everything just works fine
   (let [resp (request {:url "http://127.0.0.1:8000"})]
     (println resp)))

  ;; TODO, how to write the macro?
  (defmacro select [& body])
  (select [resp [server1 (request {:url "http://127.0.0.1:8000"})
                 server2 (request {:url "http://127.0.0.2:8000"})
                 timeout (timeout 100)]]
          ;; ok, resp is what response first, usefull for load-balancing, etc
          ;; model after go's approch to concurrency by using select, chanel, go

          ;; since Clojure has powerful macro, macro means synatx abstraction
          ;; It's possible implement something alike?
          ))
