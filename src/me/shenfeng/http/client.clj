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
  "Low-level HTTP request fn:

    * When given a callback fn, asynchronously calls that fn with the HTTP
      response (or exception) and returns nil.
    * When no callback is given, returns a promise object to which the HTTP
      response (or exception) will be asynchronously delivered.

  In most cases you'll want to use the higher-level `request` macro."
  [{:keys [client url method headers body timeout user-agent callback]
    :or   {method :get user-agent "http-kit/1.3" client (get-default-client)}}]
  (let [uri (URI. url)
        method (case method
                 :get  HttpMethod/GET
                 :post HttpMethod/POST
                 :put  HttpMethod/PUT)
        prom (when-not callback (promise))]
    (.exec ^HttpClient client uri method headers ; TODO :timeout, :user-agent
           body
           Proxy/NO_PROXY ; TODO Remove?
           (RespListener.
            (reify IResponseHandler
              (onSuccess [this status headers body]
                (let [resp {:body body
                            :headers (normalize-headers headers true)
                            :status status}]
                  (if callback
                    (callback resp)
                    (deliver prom resp))))
              (onThrowable [this t]
                (let [e (Exception. t)]
                  (if callback
                    (callback e)
                    (deliver prom e)))))))
    prom))

(comment (request* {:url "http://www.cnn.com/"})
         (request* {:url "http://www.cnn.com/" :callback #(println %)}))

(defmacro request
  "High-level request fn. TODO: Docstring"
  [{:keys [async? timeout] :as options :or {timeout 40000}} resp & handler-forms]
  `(let [options# ~(assoc options :timeout timeout)
         handle-resp# (fn [resp#]
                        (if (instance? Exception resp#)
                          (throw resp#)
                          (let [~resp resp#]
                            ~@handler-forms)))]
     (if ~async?
       (request* (assoc options# :callback handle-resp#))
       (handle-resp# (deref (request* options#) ~timeout
                            (Exception. "HTTP response timeout"))))))

(comment

  ;; TODO Test that these both work as expected, incl. error handling

  ;; Synchronous
  (try (request {:url "http://www.cnn.com"}
                {:keys [body]} (println body) body)
       (catch Exception e
         (println e)))

  ;; Asynchronous
  (request {:url "http://www.cnn.com" :async? true}
           {:keys [body]} (try (println body) body
                               (catch Exception e
                                 (println e)))))

(comment
  (defmacro first-resp [{:keys [timeout]} & body])
  (first-resp [resp [server1 (request {:url "http://127.0.0.1:8000"})
                     server2 (request {:url "http://127.0.0.2:8000"})]]
          ;; resp should get its value from whichever request returns first.
          ;; Useful for load-balancing, etc.

          ;; Should be possible to do this via low-level response promise
          ;; objects.
          ))