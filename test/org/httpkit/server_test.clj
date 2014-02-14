(ns org.httpkit.server-test
  (:use clojure.test
        ring.middleware.file-info
        org.httpkit.test-util
        [clojure.java.io :only [input-stream]]
        [ring.adapter.jetty :only [run-jetty]]
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]])
        org.httpkit.server
        org.httpkit.timer)
  (:require [clj-http.client :as http]
            [org.httpkit.ws-test :as ws]
            [org.httpkit.client :as client]
            [clj-http.util :as u])
  (:import [java.io File FileOutputStream FileInputStream]
           org.httpkit.SpecialHttpClient))

(defn file-handler [req]
  {:status 200
   :body (let [l (to-int (or (-> req :params :l) "5024000"))]
           (gen-tempfile l ".txt"))})

(defn inputstream-handler [req]
  (let [l (-> req :params :l to-int)
        file (gen-tempfile l ".txt")]
    {:status 200
     :body (FileInputStream. file)}))

(defn many-headers-handler [req]
  (let [count (or (-> req :params :count to-int) 20)]
    {:status 200
     :headers (assoc
                  (into {} (map (fn [idx]
                                  [(str "key-" idx) (str "value-" idx)])
                                (range 0 (inc count))))
                "x-header-1" ["abc" "def"])}))

(defn multipart-handler [req]
  (let [{:keys [title file]} (:params req)]
    {:status 200
     :body (str title ": " (:size file))}))

(defn async-handler [req]
  (with-channel req channel
    (send! channel {:status 200 :body "hello async"})))

(defn async-just-body [req]
  (with-channel req channel
    (send! channel "just-body")))

(defn async-response-handler [req]
  (with-channel req channel
    (send! channel {:status 200 :body "hello async"})))

(defn streaming-handler [req]
  (let [s (or (-> req :params :s) (subs const-string 0 1024))
        n (+ (rand-int 128) 10)
        seqs (partition n n '() s)]     ; padding with empty
    (with-channel req channel
      (on-close channel close-handler)
      (send! channel (first seqs) false)
      (doseq [p (rest seqs)]
        ;; should only pick the body if a map
        (send! channel (if (rand-nth [true false])
                         p
                         {:body p})
               false))        ;; do not close
      (send! channel "" true) ;; same as (close channel)
      )))

(defn slow-server-handler [req]
  (with-channel req channel
    (on-close channel close-handler)
    (send! channel "hello world")
    (schedule-task 10     ; 10ms
                   (send! channel "hello world 2"))
    (schedule-task 20
                   (send! channel "finish")
                   (close channel))))

(defn async-with-timeout [req]
  (let [time (to-int (-> req :params :time))
        cancel (-> req :params :cancel)]
    (with-channel req channel
      (with-timeout send! time
        (send! channel {:status 200
                        :body (str time "ms")})
        (when cancel
          (send! channel {:status 200 ; should return ok
                          :body "canceled"}))))))

(defn streaming-demo [request]
  (let [time (Integer/valueOf (or (-> request :params :i) 200))]
    (with-channel request channel
      (on-close channel (fn [status]
                          (println channel "closed" status)))
      (let [id (atom 0)]
        ((fn sent-messge []
           (send! channel (str "message from server #" (swap! id inc)))
           (when (open? channel)
             (schedule-task time (sent-messge)))))))))

(defroutes test-routes
  (GET "/" [] "hello world")
  (GET "/timeout" [] async-with-timeout)
  (GET "/headers" [] many-headers-handler)
  (ANY "/spec" [] (fn [req] (pr-str (dissoc req :body :async-channel))))
  (GET "/string" [] (fn [req] {:status 200
                               :headers {"Content-Type" "text/plain"}
                               :body "Hello World"}))
  (GET "/iseq" [] (fn [req] {:status 200
                             :headers {"Content-Type" "text/plain"}
                             :body (range 1 10)}))
  (GET "/file" [] (wrap-file-info file-handler))
  (GET "/ws" [] (fn [req]
                  (with-channel req con
                    (on-receive con (fn [mesg] (send! con mesg))))))
  (context "/ws2" [] ws/test-routes)
  (GET "/inputstream" [] inputstream-handler)
  (POST "/multipart" [] multipart-handler)
  (POST "/chunked-input" [] (fn [req] {:status 200
                                       :body (str (:content-length req))}))
  (GET "/length" [] (fn [req]
                      (let [l (-> req :params :length to-int)]
                        (subs const-string 0 l))))
  (GET "/null" [] (fn [req] {:status 200 :body nil}))
  (GET "/demo" [] streaming-demo)

  (GET "/async" [] async-handler)
  (GET "/slow" [] slow-server-handler)
  (GET "/streaming" [] streaming-handler)
  (GET "/async-response" [] async-response-handler)
  (GET "/async-just-body" [] async-just-body)
  (ANY "*" [] (fn [req] (pr-str (dissoc req :async-channel)))))

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

(deftest test-many-headers
  (doseq [c (range 5 40)]
    (let [resp (http/get (str "http://localhost:4347/headers?count=" c))]
      (is (= (:status resp) 200))
      (is (= (get-in resp [:headers (str "key-" c)]) (str "value-" c))))))

(deftest test-body-file
  (doseq [length (range 1 (* 1024 1024 8) 1439987)]
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

;;;;; async

(deftest test-timer
  (let [resp (http/get "http://localhost:4347/timeout?time=100&cancel=true")]
    (is (= (:status resp) 200))
    (is (= (:body resp) "canceled")))
  (let [resp (http/get "http://localhost:4347/timeout?time=100")]
    (is (= (:status resp) 200))
    (is (= (:body resp) "100ms"))))

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

(deftest test-streaming-body
  (doseq [_ (range 1 2)]
    (let [s (subs const-string 0 (+ (rand-int 1024) 256))
          resp @(client/get (str "http://localhost:4347/streaming?s=" s))]
      (is (= (:status resp) 200))
      (is (:headers resp))
      (is (= (:body resp) s))
      (check-on-close-called)))
  (doseq [_ (range 1 2)]
    (let [s (subs const-string 0 (+ (rand-int 1024) 256))
          resp @(client/get (str "http://localhost:4347/streaming?s=" s)
                            {:headers {"Connection" "close"}})]
      (is (= (:status resp) 200))
      (is (:headers resp))
      (is (= (:body resp) s))
      (check-on-close-called))))

(deftest test-client-abort-server-receive-on-close
  (doseq [i (range 0 2)]
    (SpecialHttpClient/getPartial "http://localhost:4347/slow")
    (check-on-close-called))
  (let [resp @(client/get (str "http://localhost:4347/slow"))] ; blocking wait
    (is (= 200 (:status resp)))))

(deftest test-multipart
  (let [title "This is a pic"
        size 102400
        resp (http/post "http://localhost:4347/multipart"
                        {:multipart [{:name "title" :content title}
                                     {:name "file" :content (gen-tempfile size ".jpg")}]})]
    (is (= (:status resp) 200))
    (is (= (str title ": " size) (:body resp)))))

(deftest test-uri-len
  (doseq [_ (range 0 50)]
    (let [path (str "/abc" (subs const-string 0 (rand-int 4000)))
          resp @(client/get (str "http://localhost:4347" path))]
      (is (= 200 (:status resp)))
      (is (= path (-> resp :body read-string :uri)))))
  (doseq [_ (range 0 20)]
    (let [path (str "/abc" (subs const-string 0 (+ (rand-int 4000) 4096)))
          resp @(client/get (str "http://localhost:4347" path))]
      (is (= 414 (:status resp))))))

(deftest test-client-sent-one-byte-a-time
  (doseq [_ (range 0 4)]
    (let [resp (SpecialHttpClient/slowGet "http://localhost:4347/")]
      (is (re-find #"200" resp))
      (is (re-find #"hello world" resp)))))

(deftest test-decoding-100cpu           ; regression
  ;; request + request sent to server, wait for 2 server responses
  (let [resp (SpecialHttpClient/get2 "http://localhost:4347/")]
    (= 2 (count (re-seq #"hello world" resp)))
    (= 2 (count (re-seq #"200" resp)))))

(deftest test-http10-keepalive
  ;; request + request sent to server, wait for 2 server responses
  (let [resp (SpecialHttpClient/http10 "http://localhost:4347/")]
    (is (re-find #"200" resp))
    (is (re-find #"Keep-Alive" resp))))

(deftest test-ipv6
  ;; TODO add more
  (is (= "hello world" (:body (http/get "http://[::1]:4347/")))))

(deftest test-chunked-encoding
  (let [size 4194304
        resp (http/post "http://localhost:4347/chunked-input"
                        ;; no :length, HttpClient will split body as 2k chunk
                        ;; server need to decode the chunked encoding
                        {:body (input-stream (gen-tempfile size ".jpg"))})]
    (is (= 200 (:status resp)))
    (is (= (str size) (:body resp)))))

;;; start a test server, for test or benchmark
(defonce tmp-server (atom nil))

(defn -main [& args]
  (when-let [server @tmp-server]
    (server))
  ;; start a jetty server with https for https client test
  (run-jetty (site test-routes) {:port 14347
                                 :join? false
                                 :ssl-port 9898
                                 :ssl? true
                                 :key-password "123456"
                                 :keystore "test/ssl_keystore"})
  (reset! tmp-server (run-server (site test-routes) {:port 9090
                                                     :queue-size 102400}))
  (println "server started at 0.0.0.0:9090"))

;;; Test graceful shutdown
(defn- slow-request-handler [sleep-time]
  (fn [request]
    (try
      (Thread/sleep sleep-time) {:body "ok"}
      (catch Exception e
        {:status 500}))))

(deftest test-get-local-port
  (let [server (run-server (site test-routes) {:port 0})]
    (is (> (:local-port (meta server)) 0))
    (server)))

(deftest test-immediate-close-kills-inflight-requests
  (let [server (run-server (slow-request-handler 2000) {:port 3474})
        resp (future (try (http/get "http://localhost:3474")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server)
    (is (= "fail" (:status @resp)))))

(deftest test-graceful-close-kills-long-inflight-requests
  (let [server (run-server (slow-request-handler 2000) {:port 3474})
        resp (future (try (http/get "http://localhost:3474")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server :timeout 100)
    (is (= "fail" (:status @resp)))))

(deftest test-graceful-close-responds-to-inflight-requests
  (let [server (run-server (slow-request-handler 500) {:port 3474})
        resp (future (try (http/get "http://localhost:3474")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server :timeout 3000)
    (is (= 200 (:status @resp)))))
