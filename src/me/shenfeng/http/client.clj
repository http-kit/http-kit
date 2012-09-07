(ns me.shenfeng.http.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            ITextHandler IBinaryHandler BinaryRespListener TextRespListener]
           [java.net Proxy Proxy$Type URI]))

(defonce client (atom nil))

(def no-proxy Proxy/NO_PROXY)

(defn- trasform-header [headers keyify?]
  (reduce (fn [m [k v]]
            (assoc m (if keyify? (keyword (str/lower-case k))
                         (str/lower-case k)) v))
          {} headers))

(defn- gen-handler [cb binary? keyify?]
  (if binary?
    (BinaryRespListener. (reify IBinaryHandler
                           (onSuccess [this status headers bytes]
                             (cb {:body bytes
                                  :headers (trasform-header headers keyify?)
                                  :status status}))
                           (onThrowable [this t]
                             (cb {:body t}))))
    (TextRespListener. (reify ITextHandler
                         (onSuccess [this status headers str]
                           (cb {:body str
                                :headers (trasform-header headers keyify?)
                                :status status}))
                         (onThrowable [this t]
                           (cb {:body t}))))))

;;; init http client, should be called before post and get
(defn init [& {:keys [timeout user-agent instance]
               :or {timeout 40000 user-agent "http-kit/1.1"}}]
  (when (nil? @client)
    (if instance
      (reset! client instance)
      (reset! client (HttpClient. (HttpClientConfig. timeout user-agent))))))

(defn get [{:keys [url headers cb proxy binary? keyify?]
            :or {proxy no-proxy headers {} keyify? true cb (fn [& args])}}]
  (.get ^HttpClient @client
        (URI. url) headers proxy (gen-handler cb binary? keyify?)))

(defn post [{:keys [url headers body cb proxy keyify? binary?]
             :or {proxy no-proxy headers {} keyify? true cb (fn [& args])}}]
  (.post ^HttpClient @client
         (URI. url) headers body proxy (gen-handler cb binary? keyify?)))
