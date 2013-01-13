(ns me.shenfeng.http.client-test
  (:use clojure.test
        [me.shenfeng.http.server :only [run-server]]
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]]))
  (:require [me.shenfeng.http.client :as http]))

(defroutes test-routes
  (GET "/get" [] "hello world")
  (POST "/post" [] "hello world")
  (ANY "/ua" [] (fn [req] ((-> req :headers) "user-agent")))
  (POST "/form-params" [] (fn [req] (-> req :params :param1))))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (site test-routes) {:port 4347})]
                        (try (f) (finally (server))))))

(deftest test-http-client
  (is (= 200 (:status @(http/get "http://127.0.0.1:4347/get"))))
  (is (= 200 (:status @(http/get "http://127.0.0.1:4347/get"))))
  (is (= 404 (:status @(http/get "http://127.0.0.1:4347/404"))))
  (is (= 200 (:status @(http/post "http://127.0.0.1:4347/post"))))
  (is (= 200 (:status @(http/post "http://127.0.0.1:4347/post"))))
  (is (= 404 (:status @(http/get "http://127.0.0.1:4347/404"))))
  (doseq [_ (range 1 100)]
    (let [requests (doall (map (fn [url]
                                 (http/get url))
                               ;; concurrency 20
                               (repeat 20 "http://127.0.0.1:4347/get")))]
      (doseq [r requests]
        (is (= 200 (:status @r)))))))

(deftest test-http-client-user-agent
  (let [ua "test-ua"
        url "http://127.0.0.1:4347/ua"]
    (is (= ua (:body @(http/get url {:user-agent ua}))))
    (is (= ua (:body @(http/post url {:user-agent ua}))))))

(deftest test-http-client-form-params
  (let [url "http://127.0.0.1:4347/form-params"
        value "value"]
    (is (= value (:body @(http/post url {:form-params {:param1 value}}))))))

(deftest test-http-client-async
  (let [url "http://127.0.0.1:4347/form-params"
        p (http/post url {:form-params {:param1 "value"}} {:keys [status body]}
                     (is (= 200 status))
                     (is (= "value" body)))]
    @p)) ;; wait
