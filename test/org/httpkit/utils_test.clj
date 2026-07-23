(ns org.httpkit.utils-test
  (:use clojure.test)
  (:require [clojure.java.io :as io])
  (:import org.httpkit.BytesInputStream
           org.httpkit.DynamicBytes
           org.httpkit.HeaderMap
           org.httpkit.HttpStatus
           org.httpkit.HttpUtils
           java.net.URI
           java.nio.charset.Charset))

(defn charset [name] (Charset/forName name))

(defn detect-charset [headers ^String body]
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

(deftest test-charset-parameter
  (is (= (charset "utf-8")
         (HttpUtils/parseCharset "text/plain; Charset=\"UTF-8\"; version=1")))
  (is (= (charset "iso-8859-1")
         (HttpUtils/parseCharset "text/plain; foo=x; charset = ISO-8859-1")))
  (is (nil? (HttpUtils/parseCharset "text/plain; notcharset=utf-8"))))

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
  (is (= "%20!%22#$%&'()*+,-./0123456789:;%3C=%3E?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[%5C]%5E_%60abcdefghijklmnopqrstuvwxyz%7B%7C%7D~"
         (HttpUtils/encodeURI " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"))))

(deftest test-request-targets
  (is (= "/p?q=%E6%B2%88%E9%94%8B%20x"
         (HttpUtils/getPath (URI. "http://example.com/p?q=沈锋%20x#fragment"))))
  (is (= "http://example.com/p?q=%E6%B2%88%E9%94%8B%20x"
         (HttpUtils/getProxyTarget
           (URI. "http://example.com/p?q=沈锋%20x#fragment")))))

(deftest test-http-status-bounds
  (is (thrown? IllegalArgumentException (HttpStatus/valueOf 99)))
  (is (thrown? IllegalArgumentException (HttpStatus/valueOf 1000))))

(deftest test-control-frame-bounds
  (is (thrown? IllegalArgumentException
        (HttpUtils/WsEncode (byte 8) (byte-array 126) 126))))

(deftest test-bytes-input-stream-contract
  (let [stream (BytesInputStream. (byte-array 0) 0)]
    (is (zero? (.read stream (byte-array 0) 0 0)))
    (is (= -1 (.read stream (byte-array 1) 0 1)))
    (is (thrown? NullPointerException (.read stream nil 0 0)))
    (is (thrown? IndexOutOfBoundsException
          (.read stream (byte-array 1) 1 1))))
  (is (thrown? IndexOutOfBoundsException
        (BytesInputStream. (byte-array 1) 2))))

(deftest test-get-host
  (is (= "shenfeng.me" (HttpUtils/getHost (URI. "http://shenfeng.me/what"))))
  (is (= "shenfeng.me:7979" (HttpUtils/getHost (URI. "http://shenfeng.me:7979/what")))))

(deftest test-headermap
  (let [m ^HeaderMap (HeaderMap.)]
    (doseq [i (range 1 70)]
      (.put m (str "key-" i) (str "value-" i))
      (doseq [j (range 1 i)]
        (is (= (str "value-" j) (.get m (str "key-" j))))
        (is (.containsKey m (str "key-" j))))
      (is (not (.containsKey m (str "key-" (inc i))))))))
