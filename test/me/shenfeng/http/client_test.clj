(ns me.shenfeng.http.client-test
  (:use clojure.test
        [me.shenfeng.http.server :only [run-server]]
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]]))
  (:require [me.shenfeng.http.client :as http]
            [clj-http.client :as clj-http]))

(defroutes test-routes
  (GET "/get" [] "hello world")
  (POST "/post" [] "hello world")
  (ANY "/ua" [] (fn [req] ((-> req :headers) "user-agent")))
  (POST "/form-params" [] (fn [req] (-> req :params :param1))))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (site test-routes) {:port 4347})]
                        (try (f) (finally (server))))))

(defmacro bench
  [title & forms]
  `(let [start# (. System (nanoTime))]
     ~@forms
     (prn (str ~title "Elapsed time: "
               (/ (double (- (. System (nanoTime)) start#)) 1000000.0)
               " msecs"))))

(deftest test-http-client
  (is (= 200 (:status @(http/get "http://127.0.0.1:4347/get"))))
  (is (= 200 (:status @(http/get "http://127.0.0.1:4347/get"))))
  (is (= 404 (:status @(http/get "http://127.0.0.1:4347/404"))))
  (is (= 200 (:status @(http/post "http://127.0.0.1:4347/post"))))
  (is (= 200 (:status @(http/post "http://127.0.0.1:4347/post"))))
  (is (= 404 (:status @(http/get "http://127.0.0.1:4347/404"))))
  (let [url "http://127.0.0.1:4347/get"]
    (doseq [_ (range 0 10)]
      (let [requests (doall (map (fn [u] (http/get u)) (repeat 20 url)))]
        (doseq [r requests]
          (is (= 200 (:status @r))))))
    (doseq [_ (range 0 200)]
      (is (= 200 (:status @(http/get url)))))))

;; On Macbook Air:
;; "clj-http, concurrency 1, 2000 requests: Elapsed time: 9891.066 msecs"
;; "http-kit, concurrency 10, 2000 requests: Elapsed time: 766.363 msecs"
;; "http-kit, concurrency 1, 2000 requests: Elapsed time: 1624.183 msecs"

(deftest performance-bench
  (let [url "http://127.0.0.1:4347/get"]
    (Thread/sleep 1500)
    (bench "clj-http, concurrency 1, 2000 requests: "
           (doseq [_ (range 0 2000)] (clj-http/get url)))
    (Thread/sleep 1500)
    (bench "http-kit, concurrency 10, 2000 requests: "
           (doseq [_ (range 0 200)]
             (let [requests (doall (map (fn [u] (http/get u))
                                        (repeat 10 url)))]
               (doseq [r requests] @r)))) ; wait
    (Thread/sleep 1500)
    (bench "http-kit, concurrency 1, 2000 requests: "
           (doseq [_ (range 0 2000)] @(http/get url)))))

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
