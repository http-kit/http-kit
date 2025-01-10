(ns org.httpkit.server-test
  (:use clojure.test
        ring.middleware.file-info
        org.httpkit.test-util
        [clojure.java.io :only [input-stream]]
        [ring.adapter.jetty :only [run-jetty]]
        (compojure [core :only [defroutes GET POST ANY context]]
                   [handler :only [site]])
        org.httpkit.server
        org.httpkit.timer)
  (:require [clj-http.client :as http]
            [org.httpkit.ws-test :as ws]
            [org.httpkit.client :as client])
  (:import [java.io FileInputStream]
           org.httpkit.SpecialHttpClient
           [java.net InetSocketAddress]
           [java.nio.channels ServerSocketChannel]
           (java.nio.file Files)
           (java.util.concurrent ThreadPoolExecutor TimeUnit ArrayBlockingQueue)
           (org.apache.http NoHttpResponseException)))

(defn file-handler [req]
  {:status 200
   :body (let [l (to-int (or (-> req :params :l) "5024000"))]
           (gen-tempfile l ".txt"))})

(defn inputstream-handler [req]
  (let [l (-> req :params :l to-int)
        file (gen-tempfile l ".txt")]
    {:status 200
     :body (FileInputStream. file)}))

(defn bytearray-handler [req]
  (let [l (-> req :params :l to-int)
        file (gen-tempfile l ".txt")]
    {:status 200
     :body (Files/readAllBytes (.toPath file))}))

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
  (as-channel req {:on-open
                   #(send! % {:status 200 :body "hello async"})}))

(defn async-just-body [req]
  (as-channel req {:on-open
                   #(send! % "just-body")}))

(defn streaming-handler [req]
  (let [s (or (-> req :params :s) (subs const-string 0 1024))
        n (+ (rand-int 128) 10)
        seqs (partition n n '() s)]     ; padding with empty
    (as-channel req
                {:on-close (fn [channel status]
                             (close-handler status))
                 :on-open
                 (fn [channel]
                   (send! channel (first seqs) false)
                   (doseq [p (rest seqs)]
                     ;; should only pick the body if a map
                     (send! channel (if (rand-nth [true false])
                                 p
                                 {:body p})
                            false)) ;; do not close
                   (send! channel "" true))} ;; same as (close channel)
                )))

(defn slow-server-handler [req]
  (as-channel req
              {:on-close
               (fn [channel status]
                 (close-handler status))
               :on-open (fn [channel]
                          (send! channel "hello world")
                          (schedule-task 10     ; 10ms
                                         (send! channel "hello world 2"))
                          (schedule-task 20
                                         (send! channel "finish")
                                         (close channel)))}))

(defn async-with-timeout [req]
  (let [time (to-int (-> req :params :time))
        cancel (-> req :params :cancel)]
    (as-channel req
                {:on-open (fn [channel]
                            (with-timeout send! time
                              (send! channel {:status 200
                                              :body (str time "ms")})
                              (when cancel
                                (send! channel {:status 200 ; should return ok
                                                :body "canceled"}))))})))

(defn streaming-demo [request]
  (let [time (Integer/valueOf (or ^String (-> request :params :i) 200))]
    (as-channel request
                {:on-close (fn [channel status]
                             (println channel "closed" status))
                 :on-open (fn [channel]
                            (let [id (atom 0)]
                              ((fn sent-messge []
                                 (send! channel (str "message from server #" (swap! id inc)))
                                 (when (open? channel)
                                   (schedule-task time (sent-messge)))))))})))

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
  (GET "/iseq-empty" [] (fn [req] {:status 200
                                   :headers {"Content-Type" "text/plain"}
                                   :body '()}))
  (GET "/file" [] (wrap-file-info file-handler))
  (GET "/ws" [] (fn [req]
                  (as-channel req
                              {:on-receive (fn [channel mesg]
                                             (send! channel mesg))})))
  (context "/ws2" [] ws/test-routes)
  (GET "/inputstream" [] inputstream-handler)
  (GET "/bytearray" [] bytearray-handler)
  (POST "/multipart" [] multipart-handler)
  (POST "/chunked-input" [] (fn [req] {:status 200
                                       :body (str (:content-length req))}))
  (GET "/length" [] (fn [req]
                      (let [l (-> req :params :length to-int)]
                        {:status 200
                         ;; this is wrong, but server should correct it
                         :headers {"content-length" 10000}
                         :body (subs const-string 0 l)})))
  (GET "/null" [] (fn [req] {:status 200 :body nil}))
  (GET "/demo" [] streaming-demo)

  (GET "/async" [] async-handler)
  (GET "/slow" [] slow-server-handler)
  (GET "/streaming" [] streaming-handler)
  (GET "/async-just-body" [] async-just-body)
  (GET "/i-set-date" [] (fn [req] {:status 200
                                   :headers {"Date" "Tue, 7 Mar 2017 19:52:50 GMT"}
                                   :body ""}))
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
    (is (= "c=d&a=b" (:query-string req)))
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
    (let [resp (http/get (str "http://localhost:4347/file?l=" length))]
      (is (= (:status resp) 200))
      (is (= (get-in resp [:headers "content-type"]) "text/plain"))
      (is (= length (count (:body resp)))))))

(deftest test-other-body-file
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

(deftest test-body-bytearray
  (doseq [length (range 1 (* 1024 1024 5) 1439987)] ; max 5m, many files
    (let [uri (str "http://localhost:4347/bytearray?l=" length)
          resp (http/get uri)]
      (is (= (:status resp) 200))
      (is (= length (count (:body resp)))))))

;; https://github.com/http-kit/http-kit/issues/127
(deftest test-wrong-content-length
  (doseq [length (range 1 1000 333)] ; max 5m, many files
    (let [uri (str "http://localhost:4347/length?length=" length)
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

(deftest ipv6-host-header
  (let [resp (http/get "http://localhost:4347/"
                       {:headers {"host" "[::ffff:a9fe:a9fe]"}})]
    (is (= 200 (:status resp)))))

(deftest ipv6-host-header-with-port
  (let [resp (http/get "http://localhost:4347/"
                       {:headers {"host" "[::ffff:a9fe:a9fe]:80"}})]
    (is (= 200 (:status resp)))))

(deftest ipv6-blank-host-header
  (let [resp (http/get "http://localhost:4347/"
                       {:headers {"host" ""}})]
    (is (= 200 (:status resp)))))

(deftest test-host-header-port-validity
  (is (= 200 (:status (http/get "http://localhost:4347/" {:headers {"host" "localhost"}}))))
  (is (= 200 (:status (http/get "http://localhost:4347/" {:headers {"host" "localhost:4347"}}))))
  (is (= 200 (:status (http/get "http://localhost:4347/" {:headers {"host" "localhost:"}}))))
  (is (thrown? NoHttpResponseException (http/get "http://localhost:4347/" {:headers {"host" "localhost:es"}})))
  (is (thrown? NoHttpResponseException (http/get "http://localhost:4347/" {:headers {"host" "localhost:()"}}))))

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
    (let [path (str "/abc" (subs const-string 0 (rand-int 8000)))
          resp @(client/get (str "http://localhost:4347" path))]
      (is (= 200 (:status resp)))
      (is (= path (-> resp :body read-string :uri)))))
  (doseq [_ (range 0 20)]
    (let [path (str "/abc" (subs const-string 0 (+ (rand-int 4000) 8192)))
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

(deftest ^:skip-ci test-ipv6
  ;; GitHub Actions has difficulties with [::1] IPv6 on AWS CIs,
  ;; Ref. <https://github.com/actions/runner-images/issues/668>
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
  (reset! tmp-server (run-server (site test-routes) {:port 9090}))
  (println "server started at 0.0.0.0:9090"))

;;; Test graceful stopping
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

(deftest test-application-gets-to-set-date
  (let [server (run-server (site test-routes) {:port 9090})]
    (is (= "Tue, 7 Mar 2017 19:52:50 GMT"
           (-> (http/get "http://localhost:9090/i-set-date")
               :headers
               (get "Date"))))
    (server)))

(deftest test-immediate-stop-kills-inflight-requests
  (let [server (run-server (slow-request-handler 2000) {:port 3474})
        resp (future (try (http/get "http://localhost:3474")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server)
    (is (= "fail" (:status @resp)))))

(deftest test-graceful-stop-kills-long-inflight-requests
  (let [server (run-server (slow-request-handler 2000) {:port 3474})
        resp (future (try (http/get "http://localhost:3474")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server :timeout 100)
    (is (= "fail" (:status @resp)))))

(deftest test-graceful-stop-responds-to-inflight-requests
  (let [server (run-server (slow-request-handler 500) {:port 3474})
        resp (future (try (http/get "http://localhost:3474")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server :timeout 3000)
    (is (= 200 (:status @resp)))))

(deftest test-use-external-thread-pool
  (let [test-pool (ThreadPoolExecutor. 1, 1, 0, TimeUnit/MILLISECONDS, (ArrayBlockingQueue. 1))
        server (run-server (site test-routes) {:worker-pool test-pool
                                               :port 3474})
        resp (future (try (http/get "http://localhost:3474/")
                          (catch Exception e {:status "fail"})))]
    (Thread/sleep 100)
    (server)
    (is (= "hello world" (:body @resp)))
    (is (= 200 (:status @resp)))))

(deftest test-server-status
  (let [server (run-server (slow-request-handler 500) {:port 3474 :legacy-return-value? false})
        resp_  (future (try (http/get "http://localhost:3474") (catch Exception e {:status "fail"})))]

    (deref resp_ 100 nil) ; Ensure http/get has started

    (is (= (server-status server) :running))
    (let [f_ (future (server-stop! server {:timeout 1000}))]
      (deref f_ 100 nil) ; Ensure stop call has started
      (is (= (server-status server) :stopping))
      (is (= (deref @f_ 5000 false) true)))

    (is (= (server-status server) :stopped))
    (is (= (:status @resp_) 200))))

(deftest test-server-header
  (let [url #(format "http://localhost:%s/headers?count=1" %)
        get-server-header #(get-in (into {} %) [:headers "Server"])]

    ;; Default header
    (let [server (run-server (site test-routes) {:port 3476})
          resp   (http/get (url 3476))]
      (is (= (:status resp) 200))
      (is (= "http-kit" (get-server-header resp)))
      (server))

    ;; Custom header
    (let [server (run-server (site test-routes) {:port 3477 :server-header "my-server"})
          resp   (http/get (url 3477))]
      (is (= (:status resp) 200))
      (is (= "my-server" (get-server-header resp)))
      (server))

    ;; No header
    (let [server (run-server (site test-routes) {:port 3475 :server-header nil})
          resp   (http/get (url 3475))]
      (is (= (:status resp) 200))
      (is (nil? (get-server-header resp)))
      (server))))

(deftest test-channel-reuse-async ; Ref. #375
  ;; lein test :only org.httpkit.server-test/test-channel-reuse-async
  ;; For this test, we want all client reqs to use the same socket
  (http/with-async-connection-pool {:threads 1}
    (let [ch_        (atom nil)
          responses_ (atom {}) ; {<n> <resp>}

          captured_ (atom []) ; [<send-success?> ...]
          capture!  (fn [result] (swap! captured_ conj result) result)

          resp-200  (fn [body] {:status 200, :headers {"Content-Type" "text/plain"}, :body body})
          server
          (run-server
           (fn [req]
             (case (:uri req)
               "/0"
               (as-channel req
                           {:on-open
                            (fn [ch]
                              (let [resp (resp-200 "0")]
                                (reset! ch_ ch) ; Hold channel!
                                (capture! (send! ch resp)) ; Will close @ch_
                                (capture! (send! ch resp))))})

               "/1"
               (as-channel req {:on-open
                                (fn [ch]
                                  (let [resp (resp-200 "1")]
                                    (capture! (send! @ch_ resp)) ; Try re-use @ch_
                                    (send!  ch  resp)))})

               "/2" ; Fully sync response, without `as-channel`
               (let [resp (resp-200 "2")]
                 (capture! (send! @ch_ resp)) ; Try re-use @ch_
                 resp)))

           {:port 3474})]

      (reset! tmp-server server) ; For convenience during REPL/testing

      (dotimes [n 3]
        ;; Requests to: /0, /1, /2
        (http/get (str "http://localhost:3474/" n)
                  {:async? true}
                  (fn cb [resp] (swap! responses_ #(assoc % n resp)))
                  (fn cb [ex]   (swap! responses_ #(assoc % n   ex))))
        (Thread/sleep 50))

      (Thread/sleep 100)
      (server)

      ;; After the first `send!`, @ch_ should *stay* closed,
      ;; and further attempts to `send!` to @ch_ should fail.
      (is (= [true false false false] @captured_))

      ;; Each req to uri /N should return body "N"
      (is (= "0" (:body (get @responses_ 0))))
      (is (= "1" (:body (get @responses_ 1))))
      (is (= "2" (:body (get @responses_ 2)))))))

(deftest test-channel-async-client-side-close ; Ref. #578 #579
  ;; lein test :only org.httpkit.server-test/test-channel-async-client-side-close
  (http/with-async-connection-pool {:threads 1}
    (let [ch_            (atom nil)
          on-close-run?_ (atom false)
          captured_      (atom []) ; [<send-success?> ...]
          capture!       (fn [result] (swap! captured_ conj result) result)
          sse-event
          {:status 200
           :body   "data: hello \n\n"
           :headers
           {"Content-Type"  "text/event-stream"
            "Cache-Control" "no-cache, no-store"}}

          server
          (run-server
           (fn [req]
             (as-channel req
               {:on-open  (fn [ch]  (reset! ch_ ch))
                :on-close (fn [_ _] (reset! on-close-run?_ true))}))

           {:port 3474})]

      ;; Open event-stream
      (http/get "http://localhost:3474/"
        {:timeout 100 ; Close client after 100ms
         :async?  true
         :as      :stream}
        (fn cb [_] nil)
        (fn cb [_] nil))

      (Thread/sleep 50)

      ;; Send some events without closing on the server side
      (capture! (send! @ch_ sse-event false))
      (capture! (send! @ch_ sse-event false))

      ;; Wait before closing the server
      (Thread/sleep 100)
      (server)

      (is (= [true true] @captured_) "After the first `send!`, @ch_ should not be closed.")
      (is (= true (.isClosed @ch_))  "Check that client closing the channel is detected.")
      (is (= true @on-close-run?_)   "Check that client closing the channel fired the `:on-close` handler."))))

(defroutes custom-routes
  (GET "/" [] "hello world"))

(deftest custom-providers
  (testing "can use a server based on custom address and channel providers"

    (let [calls (atom [])
          server (run-server
                  custom-routes
                  {:address-finder (fn []
                                     (swap! calls conj :address)
                                     (InetSocketAddress. "0.0.0.0" 34749))
                   :channel-factory (fn [_]
                                      (swap! calls conj :channel)
                                      (ServerSocketChannel/open))} )]
      (try
        (is (= "hello world" (:body (http/get "http://localhost:34749"))))
        (is (= [:address :channel] @calls))
        (finally (server))))))

(defn- ring-async-handler [{:keys [uri]} respond raise]
  (case uri
    "/world" (respond {:status 200, :headers {}, :body "hello world"})
    "/head"  (respond {:status 200, :headers {"X-Foo" "bar"}, :body ""})
    "/slow"  (future (Thread/sleep 1000)
                     (respond {:status 200, :headers {}, :body "hello world"}))
    "/error" (raise (ex-info "error" {}))))

(deftest test-ring-async-handlers
  (let [server (run-server ring-async-handler {:port 9091, :ring-async? true})]
    (try
      (is (= "hello world" (:body (http/get "http://localhost:9091/world"))))
      (is (= "bar" (get-in (http/get "http://localhost:9091/head") [:headers "x-foo"])))
      (is (= "hello world" (:body (http/get "http://localhost:9091/slow"))))
      (is (= 500 (:status (http/get "http://localhost:9091/error"
                                    {:throw-exceptions false}))))
      (finally (server)))))
