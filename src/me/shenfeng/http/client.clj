(ns me.shenfeng.http.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler ITextHandler IBinaryHandler BinaryRespListener
            TextRespListener RespListener$IFilter RespListener]
           [java.net Proxy Proxy$Type URI]
           me.shenfeng.http.HttpMethod))

;;; HttpClient can handler thousands of reuqest easily
;;; Just a few kilobytes of RAM  + response
(defonce client (atom nil))

(def no-proxy Proxy/NO_PROXY)

(defn- transform-header [headers keyify?]
  (reduce (fn [m [k v]]
            (assoc m (if keyify? (keyword (str/lower-case k))
                         (str/lower-case k)) v))
          {} headers))

(defn- gen-handler [cb binary? keyify?]
  (if binary?
    (BinaryRespListener. (reify IBinaryHandler
                           (onSuccess [this status headers bytes]
                             (cb {:body bytes
                                  :headers (transform-header headers keyify?)
                                  :status status}))
                           (onThrowable [this t]
                             (cb {:body t}))))
    (TextRespListener. (reify ITextHandler
                         (onSuccess [this status headers str]
                           (cb {:body str
                                :headers (transform-header headers keyify?)
                                :status status}))
                         (onThrowable [this t]
                           (cb {:body t}))))))

;;; init http client, should be called before post and get
(defn init [& {:keys [timeout user-agent instance]
               :or {timeout 40000 user-agent "http-kit/1.3"}}]
  (if instance
    (reset! client instance)
    (if (nil? @client)
      (reset! client (HttpClient. (HttpClientConfig. timeout user-agent)))
      (throw (RuntimeException. (str "already inited " @client))))))

(defn- init-if-needed []
  (when (nil? @client)
    (init)))

(defn get [{:keys [url headers cb proxy binary? keyify?]
            :or {proxy no-proxy headers {} keyify? true cb (fn [& args])}}]
  (init-if-needed)
  (.get ^HttpClient @client
        (URI. url) headers proxy (gen-handler cb binary? keyify?)))


(defn post [{:keys [url headers body cb proxy keyify? binary?]
             :or {proxy no-proxy headers {} keyify? true cb (fn [& args])}}]
  (init-if-needed)
  (.post ^HttpClient @client
         (URI. url) headers body proxy (gen-handler cb binary? keyify?)))

;;; (request req response & forms)
;;; this is a low level interface, wrap with middleware for high level functionality
(defmacro request [{:keys [url method headers body] :or {method :get}}
                   resp & handler-forms]
  `(let [uri# (URI. ~url)
         method# (case ~method
                   :get HttpMethod/GET
                   :post HttpMethod/POST
                   :put HttpMethod/PUT)]
     (init-if-needed)
     (.exec ^HttpClient @client uri# method# ~headers
            ~body
            no-proxy ;; todo proxy
            (RespListener.
             (reify IResponseHandler
               (onSuccess [this# status# headers# body#]
                 (let [~resp {:body body#
                              :headers (transform-header headers# true)
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
