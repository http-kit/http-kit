(ns org.httpkit.client-https-test
  (:require [clojure.test :refer :all]
            [org.httpkit.client :as http])
  (:import (javax.net.ssl SSLHandshakeException SSLException)))


(deftest client-https-tests
  (testing "`default-client-https` behaves similarly to `URL.openStream()`"
    (let [sslengine (http/make-ssl-engine)
          url1 "https://wrong.host.badssl.com"
          url2 "https://self-signed.badssl.com"
          url3 "https://untrusted-root.badssl.com"]

      (is (instance? SSLHandshakeException
                     (:error
                       @(http/request
                          {:client :default-https
                           :sslengine sslengine
                           :keepalive -1
                           :url url1}))))

      (is (instance? SSLException
                     (:error
                       @(http/request
                          {:client :default-https
                           :sslengine sslengine
                           :keepalive -1
                           :url url2}))))

      (is (instance? SSLException
                     (:error
                       @(http/request
                          {:client :default-https
                           :sslengine sslengine
                           :keepalive -1
                           :url url3}))))

      )
    )
  )
