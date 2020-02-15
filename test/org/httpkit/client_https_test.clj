(ns org.httpkit.client-https-test
  (:require [clojure.test :refer :all]
            [org.httpkit.sni-client :as sni]
            [org.httpkit.client :as http])
  (:import (javax.net.ssl SSLHandshakeException SSLException SSLContext)))


(deftest client-https-tests
  (testing "`sni/default-client` behaves similarly to `URL.openStream()`"
    (let [sslengine (.createSSLEngine (SSLContext/getDefault))
          https-client (force sni/default-client)
          url1 "https://wrong.host.badssl.com"
          url2 "https://self-signed.badssl.com"
          url3 "https://untrusted-root.badssl.com"]

      (is (instance? SSLHandshakeException
                     (:error
                       @(http/request
                          {:client https-client
                           :sslengine sslengine
                           :keepalive -1
                           :url url1}))))

      (is (instance? SSLException
                     (:error
                       @(http/request
                          {:client  https-client
                           :sslengine sslengine
                           :keepalive -1
                           :url url2}))))

      (is (instance? SSLException
                     (:error
                       @(http/request
                          {:client  https-client
                           :sslengine sslengine
                           :keepalive -1
                           :url url3}))))

      )
    )
  )
