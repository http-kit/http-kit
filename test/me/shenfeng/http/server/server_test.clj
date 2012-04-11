(ns me.shenfeng.http.server.server-test
  (:use clojure.test
        clojure.pprint
        ring.middleware.file-info
        me.shenfeng.http.server)
  (:require [clj-http.client :as http])
  (:import [java.io File FileOutputStream FileInputStream]
           me.shenfeng.http.server.IListenableFuture))

(defn ^File gen-tempfile
  "generate a tempfile, the file will be deleted before jvm shutdown"
  ([size extension]
     (let [tmp (doto
                   (File/createTempFile "tmp_" extension)
                 (.deleteOnExit))]
       (with-open [w (FileOutputStream. tmp)]
         (doseq [i (range size)]
           (.write w ^Integer (rem i 255))))
       tmp)))

(deftest test-netty-ring-spec
  (let [server (run-server (fn [req]
                             (is (= 4347 (:server-port req)))
                             (is (= "localhost" (:server-name req)))
                             ;; (is (= "127.0.0.1" (:remote-addr req)))
                             (is (= "/uri" (:uri req)))
                             (is (= "a=b" (:query-string req)))
                             (is (= :http (:scheme req)))
                             (is (= :get (:request-method  req)))
                             (is (= nil (:content-type req)))
                             {:status  200
                              :headers {"Content-Type" "text/plain"}
                              :body    "Hello World"})
                           {:port 4347})]
    (try
      (let [resp (http/get "http://localhost:4347/uri"
                           {:query-params {"a" "b"}})])
      (finally (server)))))


(deftest test-body-string
  (let [server (run-server (fn [req]
                             {:status  200
                              :headers {"Content-Type" "text/plain"}
                              :body    "Hello World"})
                           {:port 4347})]
    (try
      (let [resp (http/get "http://localhost:4347")]
        (is (= (:status resp) 200))
        (is (= (get-in resp [:headers "content-type"]) "text/plain"))
        (is (= (:body resp) "Hello World")))
      (finally (server)))))


(deftest test-body-file
  (let [server (run-server
                (wrap-file-info (fn [req]
                                  {:status 200
                                   :body (gen-tempfile 67 ".txt")}))
                {:port 4347})]
    (try
      (let [resp (http/get "http://localhost:4347")]
        (is (= (:status resp) 200))
        (is (= (get-in resp [:headers "content-type"]) "text/plain"))
        (is (:body resp)))
      (finally (server)))))

(deftest test-body-inputstream
  (let [server (run-server
                (fn [req]
                  {:status 200
                   :body (FileInputStream. (gen-tempfile 67000 ".txt"))})
                {:port 4347})]
    (try
      (Thread/sleep 300)
      (let [resp (http/get "http://localhost:4347")]
        (is (= (:status resp) 200))
        (is (:body resp)))
      (finally (server)))))

(deftest test-body-iseq
  (let [server (run-server (fn [req]
                             {:status  200
                              :headers {"Content-Type" "text/plain"}
                              :body    (list "Hello " "World")})
                           {:port 4347})]
    (try
      (let [resp (http/get "http://localhost:4347")]
        (is (= (:status resp) 200))
        (is (= (get-in resp [:headers "content-type"]) "text/plain"))
        (is (= (:body resp) "Hello World")))
      (finally (server)))))

(deftest test-body-null
  (let [server (run-server (fn [req]
                             {:status  204
                              :headers {"Content-Type" "text/plain"}})
                           {:port 4347})]
    (try
      (let [resp (http/get "http://localhost:4347")]
        (is (= (:status resp) 204))
        (is (= (get-in resp [:headers "content-type"]) "text/plain")))
      (finally (server)))))

(def asyc-body
  (reify IListenableFuture
    (addListener [this listener]
      (.start (Thread. (fn []
                         (println "sleep 100ms")
                         (Thread/sleep 100)
                         (.run listener)))))
    (get [this]
      {:status 204
       :headers {"Content-type" "application/json"}})))

(deftest test-body-listenablefuture
  (let [server (run-server (fn [req]
                             {:status  200
                              :body asyc-body})
                           {:port 4347})]
    (try
      (let [resp (http/get "http://localhost:4347")]
        (is (= (:status resp) 204))
        (is (= (get-in resp [:headers "content-type"]) "application/json")))
      (finally (server)))))
