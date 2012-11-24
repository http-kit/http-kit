(ns me.shenfeng.http.server.server-test
  (:use clojure.test
        clojure.pprint
        ring.middleware.file-info
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]])
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
           (.write w ^Integer i)))
       tmp)))

(defn test-get-spec [req]
  (is (= 4347 (:server-port req)))
  (is (= "127.0.0.1" (:remote-addr req)))
  (is (= "localhost" (:server-name req)))
  ;; (is (= "127.0.0.1" (:remote-addr req)))
  (is (= "/spec-get" (:uri req)))
  (is (= "a=b" (:query-string req)))
  (is (= :http (:scheme req)))
  (is (= :get (:request-method  req)))
  (is (= "utf8" (:character-encoding req)))
  (is (= nil (:content-type req)))
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(defn test-post-spec [req]
  (is (= 4347 (:server-port req)))
  (is (= "127.0.0.1" (:remote-addr req)))
  (is (= "localhost" (:server-name req)))
  ;; (is (= "127.0.0.1" (:remote-addr req)))
  (is (= "/spec-post" (:uri req)))
  (is (= "a=b" (:query-string req)))
  (is (= "c" (-> req :params :p)))
  (is (= :http (:scheme req)))
  (is (= :post (:request-method  req)))
  (is (= "application/x-www-form-urlencoded" (:content-type req)))
  (is (= "UTF-8" (:character-encoding req)))
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(def async-body
  (reify IListenableFuture
    (addListener [this listener]
      (.start (Thread. (fn []
                         (println "sleep 100ms")
                         (Thread/sleep 100)
                         (.run listener)))))
    (get [this]
      {:status 204
       :headers {"Content-type" "application/json"}})))

(defasync test-async [req] cb
  (cb {:status 200 :body "hello async"}))

(defasync test-async-just-body [req] cb
  (cb "just-body"))

(defonce tmp-server (atom nil))

(defn -main [& args]
  (when-let [server @tmp-server]
    (server))
  (reset! tmp-server (run-server test-async {:port 9898})))

(defroutes test-routes
  (GET "/spec-get" [] test-get-spec)
  (POST "/spec-post" [] test-post-spec)
  (GET "/string" [] (fn [req] {:status 200
                              :headers {"Content-Type" "text/plain"}
                              :body "Hello World"}))
  (GET "/iseq" [] (fn [req] {:status 200
                            :headers {"Content-Type" "text/plain"}
                            :body (list "Hello " "World")}))
  (GET "/file" [] (wrap-file-info (fn [req]
                                    {:status 200
                                     :body (gen-tempfile 6000 ".txt")})))
  (GET "/null" [] (wrap-file-info (fn [req]
                                    {:status 200
                                     :body nil})))
  (GET "/inputstream" [] (fn [req]
                           {:status 200
                            :body (FileInputStream.
                                   (gen-tempfile 67000 ".txt"))}))
  (GET "/async" [] (fn [req]
                     {:status  200
                      :body async-body}))
  (GET "/async2" [] test-async)
  (GET "/just-body" [] test-async-just-body))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (-> test-routes site) {:port 4347})]
                        (try (f) (finally (server))))))

(deftest test-netty-ring-spec
  (http/get "http://localhost:4347/spec-get"
            {:query-params {"a" "b"}})
  (http/post "http://localhost:4347/spec-post?a=b"
             {:content-type "application/x-www-form-urlencoded"
              :body "p=c&d=e"}))

(deftest test-body-string
  (let [resp (http/get "http://localhost:4347/string")]
    (is (= (:status resp) 200))
    (is (= (get-in resp [:headers "content-type"]) "text/plain"))
    (is (= (:body resp) "Hello World"))))

(deftest test-body-file
  (let [resp (http/get "http://localhost:4347/file")]
    (is (= (:status resp) 200))
    (is (= (get-in resp [:headers "content-type"]) "text/plain"))
    (is (:body resp))))

(deftest test-body-inputstream
  (let [resp (http/get "http://localhost:4347/inputstream")]
    (is (= (:status resp) 200))
    (is (:body resp))))

(deftest test-body-iseq
  (let [resp (http/get "http://localhost:4347/iseq")]
    (is (= (:status resp) 200))
    (is (= (get-in resp [:headers "content-type"]) "text/plain"))
    (is (= (:body resp) "Hello World"))))

(deftest test-body-null
  (let [resp (http/get "http://localhost:4347/null")]
    (is (= (:status resp) 200))
    (is (= "" (:body resp)))))

(deftest test-body-listenablefuture
  (let [resp (http/get "http://localhost:4347/async")]
    (is (= (:status resp) 204))
    (is (= (get-in resp [:headers "content-type"]) "application/json"))))

(deftest test-async2
  (let [resp (http/get "http://localhost:4347/async2")]
    (is (= (:status resp) 200))
    (is (= (:body resp) "hello async"))))

(deftest test-async2
  (let [resp (http/get "http://localhost:4347/just-body")]
    (is (= (:status resp) 200))
    (is (:headers resp))
    (is (= (:body resp) "just-body"))))
