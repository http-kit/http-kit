(ns org.httpkit.utils-test
  (:use clojure.test
        org.httpkit.test-util)
  (:require [clojure.java.io :as io])
  (:import org.httpkit.HttpUtils
           org.httpkit.DynamicBytes
           java.net.URI
           java.nio.charset.Charset))

(defn charset [name] (Charset/forName name))

(defn detect-charset [headers body]
  (let [b (doto (DynamicBytes. 128)
            (.append body))]
    (HttpUtils/detectCharset headers b)))

(deftest test-detect-charset
  (is (= (charset "gbk")
         (detect-charset {"content-type" "text/html;charset=gbk"} "")))
  (is (= (charset "utf8")
         (detect-charset {"content-type" "text/html"} "")))
  (is (= (charset "gb2312")
         (detect-charset {"content-type" "text/html"}
                         "<!doctype html><html><head><meta http-equiv=\"Content-Type\" content=\"text/html;charset=gb2312\"><title>百度一下，你就知道")))
  (is (= (charset "gbk")
         (detect-charset {"content-type" "text/html"} " <?xml version='1.0' encoding='GBK'?>")))

  (is (= (charset "gb2312")
         (detect-charset {} (slurp (io/resource "resources/xml_gb2312")))))
  (is (= (charset "gb2312")
         (detect-charset {} (slurp (io/resource "resources/html_gb2312"))))))

(deftest test-dynamicbytes
  (let [d (DynamicBytes. 1)]
    (doseq [i (range 1 1232)]
      (.append d (byte (quot i 128))))))

(deftest test-camecase
  (is (= "Accept" (HttpUtils/camelCase "accept")))
  (is (= "Accept" (HttpUtils/camelCase "Accept")))
  (is (= "Accept" (HttpUtils/camelCase "ACCEPT")))
  (is (= "User-Agent" (HttpUtils/camelCase "user-agent")))
  (is (= "User-Agent" (HttpUtils/camelCase "User-AGENT")))
  (is (= "User-Agent" (HttpUtils/camelCase "USER-agent")))
  (is (= "If-Modified-Since" (HttpUtils/camelCase "if-modified-since")))
  (is (= "If-Modified-Since" (HttpUtils/camelCase "IF-MODIFIED-SINCE")))
  (is (= "If-Modified-Since" (HttpUtils/camelCase "If-modified-since"))))

(deftest testencodeURI
  (is (= "%E6%B2%88%E9%94%8B0" (HttpUtils/encodeURI "沈锋0")))
  (is (= "%20!%22#$%25&'()*+,-./0123456789:;%3C=%3E?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[%5C]%5E_%60abcdefghijklmnopqrstuvwxyz%7B%7C%7D~"
         (HttpUtils/encodeURI " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"))))

(deftest test-get-host
  (is (= "shenfeng.me" (HttpUtils/getHost (URI. "http://shenfeng.me/what"))))
  (is (= "shenfeng.me:7979" (HttpUtils/getHost (URI. "http://shenfeng.me:7979/what")))))

(deftest test-utf8-encoding
  (let [s ^String (slurp (io/resource "resources/utf8.txt" )
                         :encoding "UTF-8")
        jdk (.getBytes s "UTF-8")
        b ^java.nio.ByteBuffer (HttpUtils/utf8Encode s)
        times 20000]
    (is (= (alength jdk) (.remaining b)))
    (doseq [i (range 0 (alength jdk))]
      (is (== (aget jdk i) (.get b))))
    (doseq [_ (range 0 times)] (.getBytes s "UTF-8"))
    (doseq [_ (range 0 times)] (HttpUtils/utf8Encode s))
    (doseq [_ (range 0 times)] (.getBytes s HttpUtils/UTF_8_CH))
    (bench "String.getByte(csn), 20000 times"
           (doseq [_ (range 0 times)] (.getBytes s "UTF-8")))
    (bench "String.getByte(cs), 20000 times"
           (doseq [_ (range 0 times)] (.getBytes s HttpUtils/UTF_8_CH)))
    (bench "HttpUtils/utf8Encode s, 20000 times"
           (doseq [_ (range 0 times)] (HttpUtils/utf8Encode s)))))
