(ns me.shenfeng.http.server-test
  (:use clojure.test
        ring.middleware.file-info
        [clojure.java.io :only [input-stream]]

        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]])
        me.shenfeng.http.server)
  (:require [clj-http.client :as http]
            [clj-http.util :as u])
  (:import [java.io File FileOutputStream FileInputStream]
           me.shenfeng.http.SlowHttpClient
           me.shenfeng.http.ws.WebSocketClient))

(defn- string-80k []
  (apply str (map char
                  (take (* 8 1024)                ; 80k
                        (apply concat (repeat (range (int \a) (int \z))))))))

;; [a..z]+
(def const-string                       ; 8M string
  (let [tmp (string-80k)]
    (apply str (repeat 1024 tmp))))

(defn ^File gen-tempfile
  "generate a tempfile, the file will be deleted before jvm shutdown"
  ([size extension]
     (let [tmp (doto
                   (File/createTempFile "tmp_" extension)
                 (.deleteOnExit))]
       (with-open [w (FileOutputStream. tmp)]
         (.write w ^bytes (.getBytes (subs const-string 0 size))))
       tmp)))

(defn to-int [int-str] (Integer/valueOf int-str))

(defasync async-handler [req] cb
  (cb {:status 200 :body "hello async"}))

(defasync async-just-body [req] cb (cb "just-body"))

(defn async-response-handler [req]
  (async-response respond
                  (future (respond
                           {:status 200 :body "hello async"}))))

(defn ws-handler [req]
  (when-ws-request req con
                   (on-mesg con (fn [msg]
                                  (let [{:keys [length times]} (read-string msg)]
                                    (doseq [_ (range 0 times)]
                                      (send-mesg con (subs const-string 0 length))))))))

(defn file-handler [req]
  {:status 200
   :body (let [l (to-int (or (-> req :params :l) "5024000"))]
           (gen-tempfile l ".txt"))})

(defn inputstream-handler [req]
  (let [l (-> req :params :l to-int)
        file (gen-tempfile l ".txt")]
    {:status 200
     :body (FileInputStream. file)}))

(defn multipart-handler [req]
  (let [{:keys [title file]} (:params req)]
    {:status 200
     :body (str title ": " (:size file))}))

(defroutes test-routes
  (GET "/" [] "hello world")
  (ANY "/spec" [] (fn [req] (pr-str (dissoc req :body))))
  (GET "/string" [] (fn [req] {:status 200
                              :headers {"Content-Type" "text/plain"}
                              :body "Hello World"}))
  (GET "/iseq" [] (fn [req] {:status 200
                            :headers {"Content-Type" "text/plain"}
                            :body (range 1 10)}))
  (GET "/file" [] (wrap-file-info file-handler))
  (GET "/inputstream" [] inputstream-handler)
  (POST "/multipart" [] multipart-handler)
  (POST "/chunked" [] (fn [req] {:status 200
                                :body (str (:content-length req))}))
  (GET "/null" [] (fn [req] {:status 200 :body nil}))
  (GET "/async" [] async-handler)
  (GET "/async-response" [] async-response-handler)
  (GET "/async-just-body" [] async-just-body)
  (GET "/ws" [] ws-handler))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (site test-routes) {:port 4347})]
                        (try (f) (finally (server))))))

(deftest test-ring-spec
  (let [req (-> (http/get "http://localhost:4347/spec?c=d"
                          {:query-params {"a" "b"}})
                :body read-string)]
    (is (= 4347 (:server-port req)))
    (is (= "127.0.0.1" (:remote-addr req)))
    (is (= "localhost" (:server-name req)))
    (is (= "/spec" (:uri req)))
    (is (= "a=b" (:query-string req)))
    (is (= :http (:scheme req)))
    (is (= :get (:request-method  req)))
    (is (= "utf8" (:character-encoding req)))
    (is (= nil (:content-type req))))
  (let [req (-> (http/post "http://localhost:4347/spec?a=b"
                           {:headers {"content-typE"
                                      "application/x-www-form-urlencoded"}
                            :body "p=c&d=e"})
                :body read-string)]
    (is (= 4347 (:server-port req)))
    (is (= "127.0.0.1" (:remote-addr req)))
    (is (= "localhost" (:server-name req)))
    (is (= "/spec" (:uri req)))
    (is (= "a=b" (:query-string req)))
    (is (= "c" (-> req :params :p)))
    (is (= :http (:scheme req)))
    (is (= :post (:request-method  req)))
    (is (= "application/x-www-form-urlencoded" (:content-type req)))
    (is (= "utf8" (:character-encoding req)))))

(deftest test-body-string
  (let [resp (http/get "http://localhost:4347/string")]
    (is (= (:status resp) 200))
    (is (= (get-in resp [:headers "content-type"]) "text/plain"))
    (is (= (:body resp) "Hello World"))))

(deftest test-body-file
  (doseq [length (range 1 (* 1024 1024 5) 1439987)]
    (let [resp (http/get "http://localhost:4347/file?l=" length)]
      (is (= (:status resp) 200))
      (is (= (get-in resp [:headers "content-type"]) "text/plain"))
      (is (= length (count (:body resp)))))))

(deftest test-body-file
  (let [resp (http/get "http://localhost:4347/file")]
    (is (= (:status resp) 200))
    (is (= (get-in resp [:headers "content-type"]) "text/plain"))
    (is (:body resp))))

(deftest test-body-inputstream
  (doseq [length (range 1 (* 1024 1024 5) 1439987)] ; max 5m, many files
    (let [uri (str "http://localhost:4347/inputstream?l=" length)
          resp (http/get uri)]
      (is (= (:status resp) 200))
      (is (= length (count (:body resp)))))))

(deftest test-body-iseq
  (let [resp (http/get "http://localhost:4347/iseq")]
    (is (= (:status resp) 200))
    (is (= (get-in resp [:headers "content-type"]) "text/plain"))
    (is (= (:body resp) (apply str (range 1 10))))))

(deftest test-body-null
  (let [resp (http/get "http://localhost:4347/null")]
    (is (= (:status resp) 200))
    (is (= "" (:body resp)))))

(deftest test-async
  (let [resp (http/get "http://localhost:4347/async")]
    (is (= (:status resp) 200))
    (is (= (:body resp) "hello async"))))

(deftest test-async
  (let [resp (http/get "http://localhost:4347/async")]
    (is (= (:status resp) 200))
    (is (= (:body resp) "hello async"))))

(deftest test-async-response
  (let [resp (http/get "http://localhost:4347/async-response")]
    (is (= (:status resp) 200))
    (is (= (:body resp) "hello async"))))

(deftest test-async-just-body
  (let [resp (http/get "http://localhost:4347/async-just-body")]
    (is (= (:status resp) 200))
    (is (:headers resp))
    (is (= (:body resp) "just-body"))))

(deftest test-multipart
  (let [title "This is a pic"
        size 102400
        resp (http/post "http://localhost:4347/multipart"
                        {:multipart [{:name "title" :content title}
                                     {:name "file" :content (gen-tempfile size ".jpg")}]})]
    (is (= (:status resp) 200))
    (is (= (str title ": " size) (:body resp)))))

(deftest test-client-sent-one-byte-a-time
  (doseq [_ (range 0 5)]
    (let [resp (SlowHttpClient/get "http://localhost:4347/")]
      (is (re-find #"200" resp))
      (is (re-find #"hello world" resp)))))

(deftest test-chunked-encoding
  (let [size 4194304
        resp (http/post "http://localhost:4347/chunked"
                        ;; no :length, HttpClient will split body as 2k chunk
                        ;; server need to decode the chunked encoding
                        {:body (input-stream (gen-tempfile size ".jpg"))})]
    (is (= 200 (:status resp)))
    (is (= (str size) (:body resp)))))

(deftest test-websocket
  (let [client (WebSocketClient. "ws://localhost:4347/ws")]
    (doseq [length (range 1 (* 1024 1024 4) 839987)]
      (let [times (rand-int 10)]
        ;; ask for a given length, make sure server understand it
        (.sendMessage client (pr-str {:length length :times times}))
        (doseq [_ (range 0 times)]
          (is (= length (count (.getMessage client)))))))
    (let [d (subs const-string 0 120)]
      ;; should return pong with the same data
      (= d (.ping client d)))
    (.close client))) ;; server's closeFrame response is checked


(defonce tmp-server (atom nil))
(defn -main [& args]
  (when-let [server @tmp-server]
    (server))
  (reset! tmp-server (run-server (site test-routes) {:port 4348
                                                     :queue-size 102400}))
  (println "server started at 0.0.0.0:4348"))
