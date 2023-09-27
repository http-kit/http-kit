(ns org.httpkit.client-test
  (:require
   [clojure.test          :as test :refer [deftest testing is are]]
   [clojure.java.io       :as io]
   [clojure.string        :as str]
   [clojure.tools.logging :as logging :refer [info warn]]

   [ring.adapter.jetty :as jetty]
   [clj-http.client    :as clj-http]
   [compojure.handler  :refer [site]]
   [compojure.route    :refer [not-found]]
   [compojure.core     :refer [defroutes GET PUT PATCH DELETE POST HEAD
                               DELETE ANY context]]

   [org.httpkit.test-util  :refer :all]
   [org.httpkit.client     :as hkc]
   [org.httpkit.server     :as hks]
   [org.httpkit.sni-client :as sni]
   [org.httpkit.utils      :as utils])

  (:import
   [java.nio.channels SocketChannel]
   java.nio.ByteBuffer
   java.nio.charset.StandardCharsets
   [org.httpkit DynamicBytes HttpMethod HttpStatus HttpUtils HttpVersion]
   [org.httpkit.client ClientSslEngineFactory Decoder IRespListener]))

(comment
  (remove-ns      'org.httpkit.client-test)
  (test/run-tests 'org.httpkit.client-test)

  (servers-start!)
  (servers-stop!))

(deftest ssl-engine-factory-race-condition
  (testing ""
    (let [errors (atom 0)]
      (dotimes [_ 8]
        (future
          (dotimes [_ 8]
            (try
              (ClientSslEngineFactory/trustAnybody)
              (catch Throwable _ (swap! errors inc))))))
      (Thread/sleep 10)
      (is (zero? @errors)))))

(defroutes test-routes
  (GET  "/get"      [] "hello")
  (POST "/post"     [] "hello")
  (ANY  "/204"      [] {:status 204})
  (ANY  "/redirect" []
    (fn [req]
      (let [total (-> req :params :total to-int)
            n (-> req :params :n to-int)
            code (to-int (or (-> req :params :code) "302"))]
        (if (>= n total)
          {:status 200 :body (-> req :request-method name)}
          {:status code
           :headers {"location" (str "redirect?total=" total "&n=" (inc n)
                                  "&code=" code)}}))))

  (ANY "/redirect-nil" [] (fn [req] {:status 302 :headers nil}))
  (POST "/multipart"   []
    (fn [req]
      (->> req
        :params
        (reduce-kv
          (fn [acc k v]
            (let [updated (if (map? v) (dissoc v :tempfile :size) v)]
              (assoc acc k updated)))
          {})
        pr-str)))

  (PATCH "/patch"        [] "hello")
  (POST  "/nested-param" [] (fn [req] (pr-str (:params req))))
  (ANY   "/method"       []
    (fn [req]
      (let [m (:request-method req)]
        {:status 200
         :headers {"x-method" (pr-str m)}})))

  (ANY    "/unicode"    [] (fn [req] (-> req :params :str)))
  (DELETE "/delete"     [] "deleted")
  (ANY    "/ua"         [] (fn [req] ((-> req :headers) "user-agent")))
  (GET    "/keep-alive" [] (fn [req] (-> req :params :id)))
  (GET    "/length"     [] (fn [req]
                             (let [l (-> req :params :length to-int)]
                               (subs const-string 0 l))))

  (GET "/multi-header" []
    (fn [req]
      {:status 200
       :headers {"x-method"  ["value1", "value2"]
                 "x-method2" ["value1", "value2", "value3"]}}))

  (GET "/p"      [] (fn [req] (pr-str (:params req))))
  (ANY "/params" [] (fn [req] (-> req :params :param1)))
  (PUT "/body"   [] (fn [req] {:body (:body req)
                               :status 200
                               :headers {"content-type" "text/plain"}}))

  (GET "/test-header" [] (fn [{:keys [headers]}] (str (get headers "test-header"))))
  (GET "/zip"         [] (fn [req] {:body "hello"}))

  (GET "/accept-encoding" []
    (fn [req]
      {:headers {"x-sent-accept-encoding" (get-in req [:headers "accept-encoding"])}
       :status  200})))

(defonce servers_ (atom nil))
(defn    servers-stop! []
  (when-let [servers @servers_]
    (when (compare-and-set! servers_ servers nil)
      (doseq [stop-fn (vals servers)] (stop-fn))
      true)))

(defn servers-start! []
  (servers-stop!)
  (let [hk    (hks/run-server  (site test-routes) {:port 4347})
        jetty (jetty/run-jetty (site test-routes)
                {:port         14347
                 :join?        false
                 :ssl-port     9898
                 :ssl?         true
                 :key-password "123456"
                 :keystore     "test/ssl_keystore"})]

    (reset! servers_
      {:hk    (fn [] (hk))
       :jetty (fn [] (.stop jetty))})

    (fn stop [] (servers-stop!))))

;;;;

(test/use-fixtures :once
  (fn [f] (servers-start!) (try (f) (finally (servers-stop!)))))

(defmacro bench [title & forms]
  `(let [start# (. System (nanoTime))]
     ~@forms
     (println (str ~title "Elapsed time: "
                (/ (double (- (. System (nanoTime)) start#)) 1000000.0)
                " msecs"))))

(defmulti callback-multi
  "Assertions to check multimethods can be used as callbacks"
  :status)

(defmethod callback-multi 200 [{status :status :as resp}] (is (= 200 status)) resp)
(defmethod callback-multi 404 [{status :status :as resp}] (is (= 404 status)) resp)
(defmethod callback-multi :default
  [{status :status :as resp}]
  (is (not= 200 status))
  (is (not= 404 status))
  resp)

(deftest test-http-client
  (doseq [host ["http://127.0.0.1:4347" "http://127.0.0.1:14347"]]
    (is (= 200 (:status @(hkc/get    (str host "/get")   (fn [resp] (is (= 200 (:status resp))) resp)))))
    (is (= 200 (:status @(hkc/post   (str host "/post")  (fn [resp] (is (= 200 (:status resp))) resp)))))
    (is (= 200 (:status @(hkc/patch  (str host "/patch") (fn [resp] (is (= 200 (:status resp))) resp)))))
    (is (= 200 (:status @(hkc/delete (str host "/delete")))))
    (is (= 200 (:status @(hkc/head   (str host "/get")))))
    (is (= 200 (:status @(hkc/post   (str host "/post")))))
    (is (= 404 (:status @(hkc/get    (str host "/404")))))
    (is (= 200 (:status @(hkc/get    (str host "/get") callback-multi))))
    (is (= 404 (:status @(hkc/get    (str host "/404") callback-multi))))
    (is (= 204 (:status @(hkc/get    (str host "/204") callback-multi))))

    (let [url (str host "/get")]
      (doseq [_ (range 0 10)]
        (let [requests (doall (map (fn [u] (hkc/get u)) (repeat 20 url)))]
          (doseq [r requests]
            (is (= 200 (:status @r))))))

      (doseq [_ (range 0 200)]
        (is (= 200 (:status @(hkc/get url))))))

    (testing "callback exception handling"
      (let [{^Exception error :error} @(hkc/get (str host "/get") (fn [_] (throw (Exception. "Exception"))))]
        (is (= "Exception" (.getMessage error))))

      (let [{^Throwable error :error} @(hkc/get (str host "/get") (fn [_] (throw (Throwable. "Throwable"))))]
        (is (= "Throwable" (.getMessage error)))))))

(deftest test-unicode-encoding
  (let [u    "高性能HTTPServer和Client"
        url  "http://127.0.0.1:4347/unicode"
        url1 (str url "?str=" (hkc/url-encode u))
        url2 (str "http://127.0.0.1:4347/unicode?str=" (hkc/url-encode u))]

    (is (= u (:body @(hkc/get url1))))
    (is (= u (:body (clj-http/get url1))))
    (is (= u (:body @(hkc/post url     {:form-params {:str u}}))))
    (is (= u (:body (clj-http/post url {:form-params {:str u}}))))
    (is (= u (:body @(hkc/get url2))))
    (is (= u (:body (clj-http/get url2))))))

(defn- rand-keep-alive []
  {:headers {"Connection" (if (> (rand-int 10) 5) "Close" "keep-alive")}})

(deftest test-keep-alive-does-not-messup
  (let [url "http://127.0.0.1:4347/keep-alive?id="]
    (doseq [id (range 0 100)]
      (is (= (str id) (:body @(hkc/get (str url id) (rand-keep-alive))))))

    (doseq [ids (partition 10 (range 0 300))]
      (let [requests
            (doall (map (fn [id]
                          (hkc/get (str url id) (rand-keep-alive)
                            (fn [resp]
                              (is (= (str id) (:body resp)))
                              resp)))
                     ids))]

        (doseq [r requests]
          (is (= 200 (:status @r))))))))

(deftest ^:benchmark performance-bench
  (let [url "http://127.0.0.1:14347/get"]
    ;; just for fun
    (bench "http-kit, concurrency 1, 3000 requests: "
      (doseq [_ (range 0 3000)] @(hkc/get url)))

    (bench "clj-http, concurrency 1, 3000 requests: "
      (doseq [_ (range 0 3000)] (clj-http/get url)))

    (bench "http-kit, concurrency 10, 3000 requests: "
      (doseq [_ (range 0 300)]
        (let [requests (doall (map (fn [u] (hkc/get u))
                                        (repeat 10 url)))]
          (doseq [r requests] @r))))) ; wait all finish

  (let [url "https://127.0.0.1:9898/get"]
    (bench "http-kit, https, concurrency 1, 1000 requests: "
      (doseq [_ (range 0 1000)] @(hkc/get url {:insecure? true})))

    (bench "http-kit, https, concurrency 10, 1000 requests: "
      (doseq [_ (range 0 100)]
        (let [requests (doall (map (fn [u] (hkc/get u {:insecure? true}))
                                (repeat 10 url)))]
          (doseq [r requests] @r)))) ; wait all finish

    (bench "clj-http, https, concurrency 1, 1000 requests: "
      (doseq [_ (range 0 1000)] (clj-http/get url {:insecure? true})))

    (bench "http-kit, https, keepalive disabled, concurrency 1, 1000 requests: "
      (doseq [_ (range 0 1000)] @(hkc/get url {:insecure? true
                                               :keepalive -1})))))

(deftest test-http-client-user-agent
  (let [ua "test-ua"
        url "http://127.0.0.1:4347/ua"]
    (is (= ua (:body @(hkc/get url {:user-agent ua}))))
    (is (= ua (:body @(hkc/post url {:user-agent ua}))))))

(deftest test-query-string
  (let [p1 "this is a test"
        query-params {:query-params {:param1 p1}}]
    (is (= p1 (:body @(hkc/get  "http://127.0.0.1:4347/params"     query-params))))
    (is (= p1 (:body @(hkc/post "http://127.0.0.1:4347/params"     query-params))))
    (is (= p1 (:body @(hkc/get  "http://127.0.0.1:4347/params?a=b" query-params))))
    (is (= p1 (:body @(hkc/post "http://127.0.0.1:4347/params?a=b" query-params))))))

(deftest test-jetty-204-decode-properly
  ;; fix #52
  (is (= 204 (:status @(hkc/get  "http://127.0.0.1:14347/204" {:timeout 20}))))
  (is (= 204 (:status @(hkc/post "http://127.0.0.1:14347/204" {:timeout 20})))))

(deftest test-http-client-form-params
  (let [url "http://127.0.0.1:4347/params"
        value "value"]
    (is (= value (:body @(hkc/post url {:form-params {:param1 value}}))))))

(deftest test-http-client-async
  (let [url "http://127.0.0.1:4347/params"
        p (hkc/post url {:form-params {:param1 "value"}}
            (fn [{:keys [status body]}]
              (is (= 200 status))
              (is (= "value" body))))]
    @p)) ;; wait

(deftest test-max-body-filter
  (is (:error @(hkc/get "http://127.0.0.1:4347/get"
                 ;; only accept response's length < 3
                 {:filter (hkc/max-body-filter 3)})))

  (is (:status @(hkc/get "http://127.0.0.1:4347/get" ; should ok
                  {:filter (hkc/max-body-filter 30000)}))))

(deftest test-http-method
  (doseq [m [:get :post :put :delete :head]]
    (is (= m (-> @(hkc/request {:method m
                                :url "http://127.0.0.1:4347/method"}
                    identity)
               :headers :x-method read-string)))))

(deftest test-string-file-inputstream-body
  (let [length (+ (rand-int (* 1024 1024 5)) 100)
        file   (gen-tempfile length ".txt")
        bodies
        [(subs const-string 0 length)    ; string
         file                            ; file
         (java.io.FileInputStream. file) ; inputstream
         [(subs const-string 0 100)  (subs const-string 100 length)] ; seqable
         (ByteBuffer/wrap (.getBytes (subs const-string 0   length))) ; byteBuffer
         ]]

    (doseq [body bodies]
      (is (= length (count (:body @(hkc/put "http://127.0.0.1:4347/body"
                                     {:body body}))))))))

(deftest test-params
  (let [url "http://a.com/biti?wvr=5&topnav=1&wvr=5&mod=logo#ccc"
        params (-> @(hkc/get "http://127.0.0.1:4347/p"
                      {:query-params {:try false :rt "url" :to 1 :u url}})
                 :body read-string)]

    (is (= url     (:u   params)))
    (is (= "false" (:try params)))))

(deftest test-output-coercion
  (let [url "http://localhost:4347/length?length=1024"]
    (let [body (:body @(hkc/get url {:as :text}))]
      (is (string? body))
      (is (= 1024 (count body))))
    (let [body (:body @(hkc/get url))] ; auto
      (is (string? body)))
    (let [body (:body @(hkc/get url {:as :auto}))] ; auto
      (is (string? body)))
    (let [body (:body @(hkc/get url {:as :stream}))]
      (is (instance? java.io.InputStream body)))
    (let [^bytes body (:body @(hkc/get url {:as :byte-array}))]
      (is (= 1024 (alength body))))))

(deftest test-https
  (let [get-url (fn [length] (str "https://localhost:9898/length?length=" length))]
    (doseq [i (range 0 2)]
      (doseq [length (repeatedly 40 (partial rand-int (* 4 1024 1024)))]
        (let [{:keys [body error status]} @(hkc/get (get-url length) {:insecure? true})]
          (if error (.printStackTrace ^Throwable error))
          (is (= length (count body)))))

      (doseq [length (repeatedly 40 (partial rand-int (* 4 1024 1024)))]
        (is (= length (-> @(hkc/get (get-url length)
                             {:insecure? true :keepalive -1})
                        :body count))))

      (doseq [length (repeatedly 40 (partial rand-int (* 4 1024 1024)))]
        (is (= length (-> @(hkc/get (get-url length)
                             (assoc (rand-keep-alive) :insecure? true))
                        :body count)))))))

(deftest test-misc-https-certs
  ;; Check to make sure an https connection works using the default trust store.
  (is (contains? @(hkc/get "https://status.github.com/api/status.json") :status))
  (is (contains? @(hkc/get "https://google.com")      :status))
  (is (contains? @(hkc/get "https://apple.com")       :status))
  (is (contains? @(hkc/get "https://microsoft.com")   :status))
  (is (contains? @(hkc/get "https://letsencrypt.org") :status)))

(deftest test-multiple-https-calls-with-same-engine
  (let [opts {:client hkc/legacy-client
              :sslengine (ClientSslEngineFactory/trustAnybody)}]
    (is (contains? @(hkc/get "https://localhost:9898" opts) :status))
    (is (contains? @(hkc/get "https://localhost:9898" opts) :status))
    (is (contains? @(hkc/get "https://localhost:9898" opts) :status))))

(deftest test-default-sni-client
  (testing "`sni/default-client` behaves similarly to `URL.openStream()`"
    (let [sslengine (hkc/make-ssl-engine)
          https-client @sni/default-client
          url0 "https://www.bbc.co.uk"
          url1 "https://wrong.host.badssl.com"
          url2 "https://self-signed.badssl.com"
          url3 "https://untrusted-root.badssl.com"]

      [(is (nil?
             (:error
              @(hkc/request
                 {:client  https-client
                  :sslengine sslengine
                  :keepalive -1
                  :url url0}))))

       (when (utils/java-version>= 11)
         ;; Specific type depends on JVM version- could be e/o
         ;; #{SSLHandshakeException RuntimeException ...}
         (is (instance? Exception
               (:error
                @(hkc/request
                   {:client https-client
                    :sslengine sslengine
                    :keepalive -1
                    :url url1})))))

       (is (instance? #_SSLException Exception
             (:error
              @(hkc/request
                 {:client  https-client
                  :sslengine sslengine
                  :keepalive -1
                  :url url2}))))

       (is (instance? #_SSLException Exception
             (:error
              @(hkc/request
                 {:client  https-client
                  :sslengine sslengine
                  :keepalive -1
                  :url url3}))))])))

;; https://github.com/http-kit/http-kit/issues/54
(deftest test-nested-param
  (let [url "http://localhost:4347/nested-param"
        params {:card {:number "4242424242424242" :exp_month "12"}}]
    (is (= params (read-string (:body @(hkc/post url {:form-params params})))))

    (is (= params (read-string (:body @(hkc/post url
                                         {:query-params {"card[number]" 4242424242424242
                                                         "card[exp_month]" 12}})))))
    (is (= params (read-string (:body (clj-http/post url {:query-params params})))))

    ;; clj-http doesn't actually process these as nested params anymore. Leaving
    ;; to maintain backward compatibility
    (is (= params (read-string (:body @(hkc/post url
                                         {:form-params {"card[number]" 4242424242424242
                                                        "card[exp_month]" 12}})))))))

(deftest test-redirect
  (testing "When location header is"
    (testing "present"
      (let [url "http://localhost:4347/redirect?total=5&n=0"]
        (is (:error @(hkc/get url {:max-redirects 3})))        ;; too many redirects
        (is (= 200 (:status @(hkc/get url {:max-redirects 6}))))
        (is (= 302 (:status @(hkc/get url {:follow-redirects false}))))
        (is (= "get" (:body @(hkc/post url {:as :text}))))     ; should switch to get method
        (is (= "post" (:body @(hkc/post url {:as :text :allow-unsafe-redirect-methods true})))) ; should not change method
        (is (= "post" (:body @(hkc/post (str url "&code=307") {:as :text})))))) ; should not change method

    (testing "nil"
      (let [url "http://localhost:4347/redirect-nil"]
        (is (:error  @(hkc/get url)))))))

(deftest test-multipart
  (let [{:keys [status body]}
        @(hkc/post "http://localhost:4347/multipart"
           {:multipart
            [{:name         "comment"
              :content      "httpkit's project.clj"}
             {:name         "file"
              :content      (clojure.java.io/file "project.clj")
              :filename     "project.clj"}
             {:name         "bytes"
              :content      (.getBytes "httpkit's project.clj" "UTF-8")}
             {:name         "custom-content-type"
              :content      (clojure.java.io/file "LICENSE.txt")
              :filename     "LICENSE.txt"
              :content-type "text/plain"}]})]

    (is (= 200 status))
    (is (= {:bytes               "httpkit's project.clj"
            :comment             "httpkit's project.clj"
            :custom-content-type {:content-type "text/plain"
                                  :filename     "LICENSE.txt"}
            :file                {:content-type "application/octet-stream"
                                  :filename     "project.clj"}}
           (read-string body)))))

(deftest test-coerce-req
  (testing "Headers should be the same regardless of multipart"
    (let [coerce-req #'org.httpkit.client/coerce-req
          request {:basic-auth ["user" "pass"]}]

      (is (= (keys (:headers (coerce-req request)))
             (remove #(= % "Content-Type")
               (keys (:headers (coerce-req (assoc request :multipart [{:name "foo" :content "bar"}])))))))))

  (testing "Multipart mixed requests shouldnt have Content-Disposition"
    (let [coerce-req #'org.httpkit.client/coerce-req
          request {:multipart [{:name "foo" :content "bar"}]
                   :multipart-mixed? true}
          coerced (coerce-req request)]

      (is (clojure.string/starts-with?
           (get-in coerced [:headers "Content-Type"] )
           "multipart/mixed; boundary="))

      (is ((complement clojure.string/includes?)
           (-> StandardCharsets/UTF_8 (.decode (:body coerced)) (.toString))
           "Content-Disposition")))))

(deftest test-header-multiple-values
  (let [resp @(hkc/get      "http://localhost:4347/multi-header" {:headers {"foo" ["bar" "baz"], "eggplant" "quux"}})
        resp2 (clj-http/get "http://localhost:4347/multi-header" {:headers {"foo" ["bar" "baz"], "eggplant" "quux"}})]
    (is (= 200 (:status resp)))
    (is (= 3 (count (str/split (-> resp :headers :x-method2) #"\n"))))
    (is (= 2 (count (str/split (-> resp :headers :x-method) #"\n"))))
    (is (= 200 (:status resp2)))))

(deftest test-headers-stringified
  (doseq [[sent expected] [["test" "test"]
                           [0 "0"]
                           ['(0) "0"]
                           ['("a" "b") "a\nb"]]]
    (let [received (:body @(hkc/get "http://localhost:4347/test-header"
                             {:headers {"test-header" sent}}))]
      (is (= received expected)))))

(defn- utf8 [^String s] (ByteBuffer/wrap (.getBytes s "UTF-8")))

(defn- decode [method buffer]
  (let [out (atom [])
        listener
        (reify IRespListener
          (onInitialLineReceived [_ v s] (swap! out conj [:init v s]))
          (onHeadersReceived [_ hs]      (swap! out conj [:headers hs]))
          (onBodyReceived [_ buf n]      (swap! out conj [:body (into [] (take n buf))]))
          (onCompleted [_]               (swap! out conj [:completed]))
          (onThrowable [_ t]             (swap! out conj [:error t])))]
    (.decode (Decoder. listener method) buffer)
    @out))

(deftest test-decode-partial-status-line
  (are [method resp events] (= (decode method (utf8 resp)) events)
    ;; The Status-Line is only parsed once there is a CRLF in the end.
    HttpMethod/GET "" []
    HttpMethod/GET "HTTP/1.1" []
    HttpMethod/GET "HTTP/1.1 200 OK" []
    HttpMethod/GET "HTTP/1.1totally-broken-line" []))

(deftest test-decode-http-version
  (are [method resp events] (= (decode method (utf8 resp)) events)
    ;; HTTP version string is parsed.
    HttpMethod/GET "HTTP/1.1 200 OK\r\n" [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]]
    HttpMethod/GET "HTTP/1.0 200 OK\r\n" [[:init HttpVersion/HTTP_1_0 HttpStatus/OK]]))

(deftest test-decode-empty-reason-phrase
  (are [method resp events] (= (decode method (utf8 resp)) events)
    ;; The Reason-Phrase (after Status-Code) may be omitted.
    HttpMethod/GET "HTTP/1.1 200 \r\n" [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]]

    ;; A Status-Line with no space after the Status-Code does not comply to the RFC 2616,
    ;; but there is probably little reason not to allow it in the parser.
    HttpMethod/GET "HTTP/1.1 200\r\n" [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]]))

(deftest test-decode-empty-headers
  (are [method resp events] (= (decode method (utf8 resp)) events)
    ;; Empty headers
    HttpMethod/GET "HTTP/1.1 200 OK\r\n\r\n" [[:init HttpVersion/HTTP_1_1 HttpStatus/OK] [:headers {}]]
    HttpMethod/GET "HTTP/1.1 200 \r\n\r\n" [[:init HttpVersion/HTTP_1_1 HttpStatus/OK] [:headers {}]]))

(deftest test-decode-headers
  (are [method resp events] (= (decode method (utf8 resp)) events)
    ;; Headers are not emitted before two consecutive CRLF's
    HttpMethod/GET "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]]

    ;; One header.
    HttpMethod/GET "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]
     [:headers {"content-length" "0"}]]))

(deftest test-decode-body
  (are [method resp events] (= (decode method (utf8 resp)) events)
    ;; Empty body with zero content-length, no matter what bytes follow.
    HttpMethod/GET "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n..."
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]
     [:headers {"content-length" "0"}]]

    ;; Expecting one byte, but no content available yet.
    HttpMethod/GET "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n"
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]
     [:headers {"content-length" "1"}]]

     ;; One byte.
    HttpMethod/GET "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n."
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]
     [:headers {"content-length" "1"}]
     [:body [46]]]

     ;; One byte. The rest is ignored.
    HttpMethod/GET "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n..."
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]
     [:headers {"content-length" "1"}]
     [:body [46]]]

    ;; The body is omitted for HEAD requests.
    HttpMethod/HEAD "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n..."
    [[:init HttpVersion/HTTP_1_1 HttpStatus/OK]
     [:headers {"content-length" "3"}]]))

(deftest deadlock-guard
  (let [n-cores (.availableProcessors (Runtime/getRuntime))
        worker  (hkc/new-worker {:n-threads n-cores})
        loop-depth 2
        bad-callback
        (fn bad-callback [n guard?]
          (when (pos? n)
            @(hkc/get "badurl"
               {:deadlock-guard? guard?
                :worker-pool (:pool worker)}
               (fn [res] (bad-callback (dec n) guard?)))))]

    (let [{:keys [error] :as resp} (bad-callback loop-depth true)]
      (is (and error (re-find #"deadlock-guard" (.getMessage ^Throwable error)))))

    (if (>= n-cores loop-depth)
      (let [{:keys [error]} (bad-callback loop-depth false)] (is (= nil error)))
      (println (str "Skipping disabled-deadlock-guard test due to low core count (" n-cores ").")))))

(deftest zip
  (is (instance? DynamicBytes (:body @(hkc/get "http://localhost:4347/zip" {:as :none})))))

(deftest adding-accept-encoding-header
  (testing "if no Accept-Encoding header present, and not explicitly disabling auto compressing response, Accept-encoding header is automatically appended"
    (let [response @(hkc/get "http://localhost:4347/accept-encoding")
          sent-accept-encoding (:x-sent-accept-encoding (:headers response))]
      (is (= sent-accept-encoding "gzip, deflate"))))

  (testing "if Accept-Encoding present, the header is sent as-is"
    (let [response @(hkc/get "http://localhost:4347/accept-encoding" {:headers {"accept-encoding" "identity"}})
          sent-accept-encoding (:x-sent-accept-encoding (:headers response))]
      (is (= sent-accept-encoding "identity"))))

  (testing "if no Accept-Encoding present, and explicitly disabling auto compressing response, Accept-encoding header is not automatically appended"
    (let [response @(hkc/get "http://localhost:4347/accept-encoding" {:auto-compression? false})
          sent-accept-encoding (:x-sent-accept-encoding (:headers response))]
      (is (nil? sent-accept-encoding))))

  (testing "if Accept-Encoding present, and explicitly disabling auto compressing response, Accept-encoding header is automatically appended"
    (let [response @(hkc/get "http://localhost:4347/accept-encoding" {:auto-compression? false :headers {"accept-encoding" "gzip"}})
          sent-accept-encoding (:x-sent-accept-encoding (:headers response))]
      (is (= sent-accept-encoding "gzip")))))

;; @(hkc/get "http://127.0.0.1:4348" {:headers {"Connection" "Close"}})

;; run many HTTP request to detect any error. urls are in file /tmp/urls, one per line
;; RUN it: scripts/run_http_requests
(def chrome "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.40 Safari/537.11")

(defn- callback [{:keys [status body headers error opts]}]
  (let [e (- (System/currentTimeMillis) (:request-start-time opts))
        url (opts :url)]
    (when (= "deflate" (:content-encoding headers))
      (warn status url " deflate; time" (str e "ms") "length: " (count body)))
    (when (instance? java.io.InputStream body)
      (warn status "=====binary=====" url body))
    (when (string? body)
      (info status url " time" (str e "ms") "length: " (count body)))
    (when error
      (warn error url))))

(defn- get-url [url]
  (let [s (System/currentTimeMillis)
        options {:request-start-time (System/currentTimeMillis)
                 :timeout 5000
                 :filter (hkc/max-body-filter 4194304)
                 :user-agent chrome}]
    (hkc/get url options callback)))

(defn- fetch-group-urls [group-idx urls]
  (let [s (System/currentTimeMillis)
        requests (doall (pmap get-url urls))]
    (doseq [r requests] @r) ; wait
    (info group-idx "takes time" (- (System/currentTimeMillis) s))))

#_(defn -main [& args]
  (let [urls (shuffle (set (line-seq (io/reader "/tmp/urls"))))]
    (info "total" (count urls) "urls")
    (doall (map-indexed fetch-group-urls (partition 1000 urls)))
    (info "all downloaded")))

(deftest pluggable-channel-providers
  (let [calls_ (atom [])
        c
        (hkc/make-client
          {:address-finder
           (fn [uri]
             (swap! calls_ conj :uri)
             (HttpUtils/getServerAddr uri))

           :channel-factory
           (fn [address]
             (swap! calls_ conj :address)
             (SocketChannel/open))})]

    (testing "Can use pluggable address and channel providers"
      (doseq [host ["http://127.0.0.1:4347" "http://127.0.0.1:14347"]]
        (is (= 200 (:status @(hkc/get (str host "/get") {:client c}
                               (fn [resp]
                                 (is (= 200 (:status resp)))
                                 resp))))))

      (is (= [:uri :address :uri :address] @calls_)))))
