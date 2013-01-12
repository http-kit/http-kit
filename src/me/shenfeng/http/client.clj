(ns me.shenfeng.http.client
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str])
  (:import [me.shenfeng.http.client HttpClientConfig HttpClient
            IResponseHandler RespListener RespListener$IFilter]
           (java.net URI URLEncoder)
           me.shenfeng.http.HttpMethod))

(defn- url-encode
  "Returns an UTF-8 URL encoded version of the given string."
  [unencoded]
  (URLEncoder/encode unencoded "UTF-8"))

(defn- transform-header [headers keyify?]
  (reduce (fn [m [k v]]
            (assoc m (if keyify? (keyword (str/lower-case k))
                         (str/lower-case k)) v))
          {} headers))

(defn- http-method [method-keyword]
  (case (or method-keyword :get)
    :get HttpMethod/GET
    :post HttpMethod/POST
    :put HttpMethod/PUT))

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
             "UTF-8"))
