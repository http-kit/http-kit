(ns me.shenfeng.http.client-test
  (:use clojure.test
        [ring.adapter.jetty :only [run-jetty]]
        [me.shenfeng.http.server :only [run-server]]
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]])
        (clojure.tools [logging :only [info]]))
  (:require [me.shenfeng.http.client :as http]
            [clojure.java.io :as io]
            [clj-http.client :as clj-http]))

(defroutes test-routes
  (GET "/get" [] "hello world")
  (POST "/post" [] "hello world")
  (ANY "/unicode" [] (fn [req] (-> req :params :str)))
  (DELETE "/delete" [] "deleted")
  (ANY "/ua" [] (fn [req] ((-> req :headers) "user-agent")))
  (GET "/keep-alive" [] (fn [req] (-> req :params :id)))
  (POST "/form-params" [] (fn [req] (-> req :params :param1))))

(use-fixtures :once (fn [f]
                      (let [server (run-server (site test-routes) {:port 4347})]
                        ;; start http-kit server & jetty server
                        (run-jetty (site test-routes) {:port 14347 :join? false})
                        (try (f) (finally (server))))))

(defmacro bench
  [title & forms]
  `(let [start# (. System (nanoTime))]
     ~@forms
     (println (str ~title "Elapsed time: "
                   (/ (double (- (. System (nanoTime)) start#)) 1000000.0)
                   " msecs"))))

(deftest test-http-client
  (doseq [host ["http://127.0.0.1:4347" "http://127.0.0.1:14347"]]
    (is (= 200 (:status @(http/get (str host "/get")))))
    (is (= 200 (:status @(http/get (str host "/get")))))
    (is (= 404 (:status @(http/get (str host "/404")))))
    (is (= 200 (:status @(http/post (str host "/post")))))
    (is (= 200 (:status @(http/delete (str host "/delete")))))
    (is (= 200 (:status @(http/head (str host "/get")))))
    (is (= 200 (:status @(http/post (str host "/post")))))
    (is (= 404 (:status @(http/get (str host "/404")))))
    (let [url (str host "/get")]
      (doseq [_ (range 0 10)]
        (let [requests (doall (map (fn [u] (http/get u)) (repeat 20 url)))]
          (doseq [r requests]
            (is (= 200 (:status @r))))))
      (doseq [_ (range 0 200)]
        (is (= 200 (:status @(http/get url))))))))

(deftest test-unicode-encoding
  (let [u "高性能HTTPServer和Client"
        url "http://127.0.0.1:4347/unicode"
        url1 (str url "?str=" u)
        url2 (str "http://127.0.0.1:4347/unicode?str=" u)]
    (is (= u (:body @(http/get url1))))
    (is (= u (:body (clj-http/get url1))))
    (is (= u (:body @(http/post url {:form-params {:str u}}))))
    (is (= u (:body (clj-http/post url {:form-params {:str u}}))))
    (is (= u (:body @(http/get url2))))
    (is (= u (:body (clj-http/get url2))))))

(defn- rand-keep-alive []
  ;; TODO has issue in linux
  {:headers {"Connection" (cond  (> (rand-int 10) 8) "Close"
                                 :else "keep-alive")}})

(deftest test-keep-alive-does-not-messup
  (let [url "http://127.0.0.1:4347/keep-alive?id="]
    (doseq [id (range 0 100)]
      (is (= (str id) (:body @(http/get (str url id))))))
    (doseq [ids (partition 10 (range 0 300))]
      (let [requests (doall (map (fn [id]
                                   (http/get (str url id) {}
                                             {:keys [body]}
                                             (is (= (str id) body))))
                                 ids))]
        (doseq [r requests]
          (is (= 200 (:status @r))))))))

(deftest performance-bench
  (doseq [{:keys [url name]} [{:url "http://127.0.0.1:14347/get"
                               :name "jetty server"}
                              {:url "http://127.0.0.1:4347/get"
                               :name "http-kit server"}]]
    (println (str "\nWarm up " name " by 10000 requests. "
                  "It may take some time\n"))
    (doseq [_ (range 0 10000)] (clj-http/get url) (http/get url))
    (bench "clj-http, concurrency 1, 4000 requests: "
           (doseq [_ (range 0 4000)] (clj-http/get url)))
    (bench "http-kit, concurrency 1, 4000 requests: "
           (doseq [_ (range 0 4000)] @(http/get url)))
    (bench "http-kit, concurrency 10, 4000 requests: "
           (doseq [_ (range 0 400)]
             (let [requests (doall (map (fn [u] (http/get u))
                                        (repeat 10 url)))]
               (doseq [r requests] @r)))))) ; wait

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

;; @(http/get "http://127.0.0.1:4348" {:headers {"Connection" "Close"}})

;; run many HTTP request to detect any error
;; RUN it: scripts/run_http_requests
(defn -main [& args]
  (let [ua "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.40 Safari/537.11"
        urls (shuffle (set (line-seq (io/reader "/tmp/urls"))))]
    (doall
     (map-indexed (fn [idx urls]
                    (println urls)
                    (let [s (System/currentTimeMillis)
                          requests (pmap (fn [url]
                                           (http/get url {:timeout 1000 :user-agent ua}
                                                     {:keys [status body] :as resp}
                                                     (let [e (- (System/currentTimeMillis) s)]
                                                       (if (instance? java.io.InputStream body)
                                                         (info status "=====binary=====" url body)
                                                         (if status
                                                           (info status url "time" (str e "ms") "length: " (count body))
                                                           (info url resp))))))
                                         urls)]
                      (doseq [r (doall requests)] @r) ; wait
                      (info idx "takes time" (- (System/currentTimeMillis) s))))
                  (partition 100 urls)))))
