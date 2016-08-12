(ns org.httpkit.client-proxy-test
  (:require [clj-http.client :as clj-http]
            [clojure.data.codec.base64 :as b64]
            [clojure.test :refer :all]
            [compojure.core :refer [GET defroutes wrap-routes]]
            [org.httpkit.client :as http]
            [org.httpkit.ring-request-proxy :refer [proxy-request]]
            [org.httpkit.server :refer [run-server]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]))

(defroutes test-routes
  (GET "/get" [] "hello world"))

(defn authenticated? [name pass]
  (and (= name "user")
       (= pass "pass")))

(def proxy-handler (proxy-request {:identifer-fn :server-name
                                   :allow-insecure-ssl true
                                   :host-fn (fn [{:keys [remote-addr server-port scheme] :as opts}]
                                              (cond
                                                (= 4347 server-port)
                                                (str "http://" remote-addr ":" server-port)
                                                (= 9898 server-port)
                                                (str "https://" remote-addr ":" server-port)))}))

(use-fixtures :once
  (fn [f]
    (let [server (run-server
                  (defaults/wrap-defaults test-routes defaults/site-defaults)
                  {:port 4347})
          ssl-server (run-jetty
                      (defaults/wrap-defaults test-routes defaults/site-defaults)
                      {:port 14347 :join? false :ssl-port 9898 :ssl? true :http? false
                       :key-password "123456" :keystore "test/ssl_keystore"})
          ssl-proxy-server (run-jetty
                            proxy-handler
                            {:port 14348 :join? false :ssl-port 9899 :ssl? true :http? false
                             :key-password "123456" :keystore "test/ssl_keystore"})
          proxy-server (run-server
                        proxy-handler
                        {:port 4348 :join? false :ssl? false})
          auth-proxy-server (run-server
                             (-> proxy-handler
                                 (wrap-basic-authentication authenticated?))
                             {:port 4349})]
      (try (f) (finally (server) (proxy-server) (auth-proxy-server)
                        (.stop ssl-server) (.stop ssl-proxy-server))))))

(deftest test-control
  (testing "no proxy, for sanity checking"
    (let [resp @(http/get "http://127.0.0.1:4347/get")]
      (is (= 200 (:status resp)))
      (is (= "hello world" (:body resp)))))
  (testing "no proxy + ssl, for sanity checking"
    (let [resp @(http/get "https://127.0.0.1:9898/get" {:insecure? true})]
      (is (= 200 (:status resp)))
      (is (= "hello world" (:body resp))))))

(deftest test-compare-to-clj
  (testing "compare to clj-http"
    (let [{clj-status :status clj-body :body :as resp-clj}
          (clj-http/get "http://127.0.0.1:4347/get" {:proxy-host "127.0.0.1"
                                                     :proxy-port 4348
                                                     :throw-exceptions false
                                                     :proxy-ignore-hosts #{}})

          {kit-status :status kit-body :body}
          @(http/get "http://127.0.0.1:4347/get"
                     {:proxy-url "http://127.0.0.1:4348"})
          {kit-status-backcompat :status kit-body-backcompat :body}
          @(http/get "http://127.0.0.1:4347/get"
                     {:proxy-host "http://127.0.0.1"
                      :proxy-port 4348})]
      (is (= clj-status kit-status))
      (is (= clj-body kit-body))
      (is (= clj-status kit-status-backcompat))
      (is (= clj-body kit-body-backcompat)))))

(deftest proxy-nonexistent
  (testing "test call nonexistent proxy and fail"
    (let [{:keys [error]} @(http/get "http://127.0.0.1:4347/get"
                                     {:proxy-url "http://127.0.0.1:4346"})]
      (is (= (type (java.net.ConnectException.)) (type error)))
      (is (= "Connection refused"
             (.getMessage ^java.net.ConnectException error))))))
(deftest http-to-http-proxy
  (testing "test call proxy successfully"
    (let [{:keys [status body]} @(http/get "http://127.0.0.1:4347/get"
                                           {:proxy-url "http://127.0.0.1:4348"})]
      (is (= 200 status))
      (is (= "hello world" body)))))
(deftest https-to-http-proxy
  (testing "test ssl call proxy successfully"
    (let [{:keys [status body]} @(http/get "https://127.0.0.1:9898/get"
                                           {:proxy-url "http://127.0.0.1:4348"})]
      (is (= 200 status))
      (is (= "hello world" body)))))
(deftest http-to-https-proxy
  (testing "test call ssl proxy successfully"
    (let [{:keys [status body]} @(http/get "http://127.0.0.1:4347/get"
                                           {:proxy-url "https://127.0.0.1:9899"
                                            :insecure? true})]
      (is (= 200 status))
      (is (= "hello world" body)))))
(deftest https-to-https-proxy
  (testing "test ssl call ssl proxy successfully"
    (let [{:keys [status body]} @(http/get "https://127.0.0.1:9898/get"
                                           {:proxy-url "https://127.0.0.1:9899"
                                            :insecure? true})]
      (is (= 200 status))
      (is (= "hello world" body)))))

(def bad-auth-header-value
  (str "Basic " (String. ^bytes (b64/encode (.getBytes "user-incorrect:pass")))))

(def auth-header-value
  (str "Basic " (String. ^bytes (b64/encode (.getBytes "user:pass")))))

(deftest test-proxy-basic-auth
  (testing "test proxy with successful auth"
    (let [{:keys [status body]}
          @(http/get "http://127.0.0.1:4347/get"
                     {:proxy-url "http://127.0.0.1:4349"
                      :headers {"Authorization" auth-header-value}})]
      (is (= 200 status))
      (is (= "hello world" body))))

  (testing "test proxy with unsuccessful auth"
    (let [{:keys [status body]}
          @(http/get "http://127.0.0.1:4347/get"
                     {:proxy-url "http://127.0.0.1:4349"
                      :headers {"Authorization" bad-auth-header-value}})]
      (is (= 401 status))
      (is (= "access denied" body)))))
