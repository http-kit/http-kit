(ns org.httpkit.client-proxy-test
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [compojure.core :refer [GET defroutes wrap-routes]]
   [clj-http.client :as clj-http]
   [clojure.data.codec.base64 :as b64]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults             :as rmwr-defaults]
   [ring.middleware.basic-authentication :as rmwr-auth]
   [org.httpkit.client :as hkc]
   [org.httpkit.server :as hks]
   [org.httpkit.ring-request-proxy]))

(comment
  (remove-ns      'org.httpkit.client-proxy-test)
  (test/run-tests 'org.httpkit.client-proxy-test)

  (servers-start!)
  (servers-stop!))

(defn authenticated? [name pass] (and (= name "user") (= pass "pass")))

(defroutes test-routes
  (GET "/get" [] "hello"))

(def proxy-handler
  (org.httpkit.ring-request-proxy/proxy-request
    {:identifer-fn :server-name
     :allow-insecure-ssl true
     :host-fn
     (fn [{:keys [remote-addr server-port scheme]}]
       (case (long server-port)
         4347 (str "http://"  remote-addr ":" server-port)
         9898 (str "https://" remote-addr ":" server-port)))}))

(defonce servers_ (atom nil))
(defn    servers-stop! []
  (when-let [servers @servers_]
    (when (compare-and-set! servers_ servers nil)
      (doseq [stop-fn (vals servers)] (stop-fn)))))

(defn servers-start! []
  (servers-stop!)
  (let [hk-default
        (hks/run-server
          (rmwr-defaults/wrap-defaults test-routes rmwr-defaults/site-defaults)
          {:port 4347})

        hk-proxy (hks/run-server proxy-handler {:port 4348 :join? false :ssl? false})
        hk-proxy-auth
        (hks/run-server
          (rmwr-auth/wrap-basic-authentication proxy-handler authenticated?)
          {:port 4349})

        jetty-ssl
        (jetty/run-jetty
          (rmwr-defaults/wrap-defaults test-routes rmwr-defaults/site-defaults)
          {:port 14347 :join? false :ssl-port 9898 :ssl? true :http? false
           :sni-host-check? false :key-password "123456" :keystore "test/ssl_keystore"})

        jetty-ssl-proxy
        (jetty/run-jetty proxy-handler
          {:port 14348 :join? false :ssl-port 9899 :ssl? true :http? false
           :sni-host-check? false :key-password "123456" :keystore "test/ssl_keystore"})]

    (reset! servers_
      {:hk-default      (fn [] (hk-default))
       :hk-proxy        (fn [] (hk-proxy))
       :hk-proxy-auth   (fn [] (hk-proxy-auth))
       :jetty-ssl       (fn [] (.stop jetty-ssl))
       :jetty-ssl-proxy (fn [] (.stop jetty-ssl-proxy))})

    (fn stop [] (servers-stop!))))

;;;;

(test/use-fixtures :once
  (fn [f] (servers-start!) (try (f) (finally (servers-stop!)))))

(deftest test-control
  [(testing "no proxy, for sanity checking"
     (let [{:keys [status body]} @(hkc/get "http://127.0.0.1:4347/get")]
       [(is (= status 200))
        (is (= body "hello"))]))

   (testing "no proxy + ssl, for sanity checking"
     (let [{:keys [status body]} @(hkc/get "https://127.0.0.1:9898/get" {:insecure? true})]
       [(is (= status 200))
        (is (= body "hello"))]))])

(deftest test-compare-to-clj
  (testing "compare to clj-http"
    (let [{clj-status :status clj-body :body :as resp-clj}
          (clj-http/get "http://127.0.0.1:4347/get"
            {:proxy-host "127.0.0.1"
             :proxy-port 4348
             :throw-exceptions false
             :proxy-ignore-hosts #{}})

          {kit-status :status kit-body :body}
          @(hkc/get "http://127.0.0.1:4347/get"
             {:proxy-url "http://127.0.0.1:4348"})

          {kit-status-backcompat :status kit-body-backcompat :body}
          @(hkc/get "http://127.0.0.1:4347/get"
             {:proxy-host "http://127.0.0.1"
              :proxy-port 4348})]

      [(is (= clj-status kit-status))
       (is (= clj-body   kit-body))
       (is (= clj-status kit-status-backcompat))
       (is (= clj-body   kit-body-backcompat))])))

(deftest proxy-nonexistent
  (testing "test call nonexistent proxy and fail"
    (let [{:keys [error]} @(hkc/get "http://127.0.0.1:4347/get"
                             {:proxy-url "http://127.0.0.1:4346"})]

      [(is (= (type (java.net.ConnectException.)) (type error)))
       (is (= "Connection refused"
             (.getMessage ^java.net.ConnectException error)))])))

(deftest http-to-http-proxy
  (testing "test call proxy successfully"
    (let [{:keys [status body]} @(hkc/get "http://127.0.0.1:4347/get"
                                   {:proxy-url "http://127.0.0.1:4348"})]
      [(is (= 200 status))
       (is (= "hello" body))])))

(deftest https-to-http-proxy
  (testing "test ssl call proxy successfully"
    (let [{:keys [status body]} @(hkc/get "https://127.0.0.1:9898/get"
                                   {:proxy-url "http://127.0.0.1:4348"})]
      [(is (= 200 status))
       (is (= "hello" body))])))

(deftest http-to-https-proxy
  (testing "test call ssl proxy successfully"
    (let [{:keys [status body]} @(hkc/get "http://127.0.0.1:4347/get"
                                   {:proxy-url "https://127.0.0.1:9899"
                                    :insecure? true})]
      [(is (= 200 status))
       (is (= "hello" body))])))

(deftest https-to-https-proxy
  (testing "test ssl call ssl proxy successfully"
    (let [{:keys [status body]} @(hkc/get "https://127.0.0.1:9898/get"
                                   {:proxy-url "https://127.0.0.1:9899"
                                    :insecure? true})]
      [(is (= 200 status))
       (is (= "hello" body))])))

(def bad-auth-header-value (str "Basic " (String. ^bytes (b64/encode (.getBytes "user-incorrect:pass")))))
(def auth-header-value     (str "Basic " (String. ^bytes (b64/encode (.getBytes "user:pass")))))

(deftest test-proxy-basic-auth
  [(testing "test proxy with successful auth"
     (let [{:keys [status body]}
           @(hkc/get "http://127.0.0.1:4347/get"
              {:proxy-url "http://127.0.0.1:4349"
               :headers {"Authorization" auth-header-value}})]

       [(is (= 200 status))
        (is (= "hello" body))]))

   (testing "test proxy with unsuccessful auth"
     (let [{:keys [status body]}
           @(hkc/get "http://127.0.0.1:4347/get"
              {:proxy-url "http://127.0.0.1:4349"
               :headers {"Authorization" bad-auth-header-value}})]

       [(is (= 401 status))
        (is (= "access denied" body))]))])
