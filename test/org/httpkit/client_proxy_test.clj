(ns org.httpkit.client-proxy-test
  (:require [clj-http.client :as clj-http]
            [clojure.data.codec.base64 :as b64]
            [clojure.test :refer :all]
            [compojure.core :refer [GET defroutes]]
            [org.httpkit.client :as http]
            [org.httpkit.server :refer [run-server]]
            [ring-request-proxy.core :as proxy]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]))

(defroutes test-routes
  (GET "/get" [] "hello world"))

(defn authenticated? [name pass]
  (and (= name "user")
       (= pass "pass")))

(use-fixtures :once
  (fn [f]
    (let [server (run-server
                  (defaults/wrap-defaults test-routes defaults/site-defaults)
                  {:port 4347})
          proxy-server (run-server
                        (proxy/proxy-request
                         {:identifer-fn :server-name
                          :host-fn (fn [{:keys [uri] :as opts}] uri)})
                        {:port 4348})
          auth-proxy-server (run-server
                             (-> (proxy/proxy-request
                                  {:identifer-fn :server-name
                                   :host-fn (fn [{:keys [uri] :as opts}] uri)})
                                 (wrap-basic-authentication authenticated?))
                             {:port 4349})]
      (try (f) (finally (server) (proxy-server) (auth-proxy-server))))))

(deftest test-control
  (testing "no proxy, for sanity checking"
    (let [resp @(http/get "http://localhost:4347/get")]
      (is (= 200 (:status resp)))
      (is (= "hello world" (:body resp))))))

(deftest test-compare-to-clj
  (testing "compare to clj-http"
    (let [{clj-status :status clj-body :body :as resp-clj}
          (clj-http/get "http://localhost:4347/get" {:proxy-host "127.0.0.1"
                                                     :proxy-port 4348
                                                     :throw-exceptions false
                                                     :proxy-ignore-hosts #{}})

          {kit-status :status kit-body :body error :error :as resp-kit}
          @(http/get "http://localhost:4347/get"
                     {:proxy "http://127.0.0.1:4348"})]
      (is (= clj-status kit-status))
      (is (= clj-body kit-body)))))

(deftest test-proxy
  (testing "test call nonexistent proxy and fail"
    (let [{:keys [error]} @(http/get "http://localhost:4347/get"
                                     {:proxy "http://127.0.0.1:4346"})]
      (is (= (type (java.net.ConnectException.)) (type error)))
      (is (= "Connection refused"
             (.getMessage ^java.net.ConnectException error)))))

  (testing "test call proxy successfully"
    (let [{:keys [status body]} @(http/get "http://localhost:4347/get"
                                           {:proxy "http://127.0.0.1:4348"})]
      (is (= 200 status))
      (is (= "hello world" body)))))

(def bad-auth-header-value
  (str "Basic " (String. ^bytes (b64/encode (.getBytes "user-incorrect:pass")))))

(def auth-header-value
  (str "Basic " (String. ^bytes (b64/encode (.getBytes "user:pass")))))

(deftest test-proxy-basic-auth
  (testing "test proxy with successful auth"
    (let [{:keys [status body]}
          @(http/get "http://localhost:4347/get"
                     {:proxy "http://127.0.0.1:4349"
                      :headers {"Authorization" auth-header-value}})]
      (is (= 200 status))
      (is (= "hello world" body))))

  (testing "test proxy with unsuccessful auth"
    (let [{:keys [status body]}
          @(http/get "http://localhost:4347/get"
                     {:proxy "http://127.0.0.1:4349"
                      :headers {"Authorization" bad-auth-header-value}})]
      (is (= 401 status))
      (is (= "access denied" body)))))
