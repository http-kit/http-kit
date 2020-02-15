(ns org.httpkit.client-https-test
  (:require [clojure.test :refer :all]
            [org.httpkit.client :as http]
            [org.httpkit.sni-client :as sni])
  (:import (javax.net.ssl SSLHandshakeException SSLException SSLContext)))


(deftest client-https-tests
  (testing "`sni/default-client` behaves similarly to `URL.openStream()`"
    (let [https-client (force sni/default-client)
          url1 "https://wrong.host.badssl.com"
          url2 "https://self-signed.badssl.com"
          url3 "https://untrusted-root.badssl.com"]

      (is (nil?
            (:error
              @(http/request
                 {:client  https-client
                  :sslengine (.createSSLEngine (SSLContext/getDefault))
                  :keepalive -1
                  :url "https://www.bbc.co.uk"}))))

      (let [exception (:error
                        @(http/request
                           {:client https-client
                            :sslengine (.createSSLEngine (SSLContext/getDefault))
                            :keepalive -1
                            :url url1}))]
                ;; Java 12 and above throws proper SSLHandshakeException
        (is (or (instance? SSLHandshakeException exception)
                ;; Java 8 throws RuntimeException at sun.security.ssl.Handshaker
                (instance? RuntimeException exception))))

      (is (instance? SSLException
                     (:error
                       @(http/request
                          {:client  https-client
                           :sslengine (.createSSLEngine (SSLContext/getDefault))
                           :keepalive -1
                           :url url2}))))

      (is (instance? SSLException
                     (:error
                       @(http/request
                          {:client  https-client
                           :sslengine (.createSSLEngine (SSLContext/getDefault))
                           :keepalive -1
                           :url url3}))))

      (is (nil?
              (:error
                @(http/request
                   {:client  https-client
                    :sslengine (.createSSLEngine (SSLContext/getDefault))
                    :keepalive -1
                    :url "https://github.com"}))
              ))

      )
    )
  )
