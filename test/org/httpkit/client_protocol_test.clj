(ns org.httpkit.client-protocol-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [org.httpkit.client :as client])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [java.net InetSocketAddress ServerSocket Socket SocketException SocketTimeoutException]
   [java.nio.channels SocketChannel]
   [java.nio.charset StandardCharsets]
   [org.httpkit ProtocolException]
   [org.httpkit.client HttpClient]))

(defn- read-request [^Socket socket]
  (let [in (.getInputStream socket)
        out (ByteArrayOutputStream.)]
    (loop [tail ""]
      (when-not (= tail "\r\n\r\n")
        (let [b (.read in)]
          (when-not (= b -1)
            (.write out b)
            (let [tail (str tail (char b))]
              (recur (subs tail (max 0 (- (count tail) 4)))))))))
    (.toString out "UTF-8")))

(defn- write! [^Socket socket s]
  (let [out (.getOutputStream socket)]
    (.write out (.getBytes ^String s StandardCharsets/US_ASCII))
    (.flush out)))

(defn- raw-server [serve]
  (let [server (ServerSocket. 0)
        done
        (future
          (try
            (serve server)
            (finally
              (.close server))))]
    {:port (.getLocalPort server)
     :done done
     :close #(.close server)}))

(defn- url [{:keys [port]}]
  (str "http://127.0.0.1:" port))

(defn- one-response [response]
  (raw-server
    (fn [^ServerSocket server]
      (with-open [socket (.accept server)]
        (read-request socket)
        (write! socket response)))))

(deftest response-framing
  (testing "interim responses are skipped"
    (let [server
          (one-response
            (str "HTTP/1.1 100 Continue\r\nX-Interim: 100\r\n\r\n"
                 "HTTP/1.1 103 Early Hints\r\nX-Interim: 103\r\n\r\n"
                 "HTTP/1.1 199 Informational\r\nX-Interim: 199\r\n\r\n"
                 "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                 "Content-Length: 2\r\nConnection: close\r\n\r\nok"))
          response @(client/get (url server) {:as :text})]
      (is (= 200 (:status response)))
      (is (= "ok" (:body response)))
      (is (nil? (:x-interim (:headers response))))
      @(:done server)))

  (testing "bodyless responses ignore individual framing headers"
    (doseq [[method status] [[:head 200] [:get 204] [:get 205] [:get 304]]
            header ["Content-Length: 999" "Transfer-Encoding: invalid"]]
      (let [release (promise)
            server
            (raw-server
              (fn [^ServerSocket server]
                (with-open [socket (.accept server)]
                  (read-request socket)
                  (write! socket
                    (str "HTTP/1.1 " status " Bodyless\r\n"
                         header "\r\n\r\n"))
                  @release)))
            request ((case method :head client/head client/get) (url server))
            response (deref request 500 ::timeout)]
        (deliver release true)
        (is (not= ::timeout response))
        (is (= status (:status response)))
        @(:done server))))

  (testing "ambiguous response framing is rejected"
    (let [server
          (one-response
            (str "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                 "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                 "2\r\nok\r\n0\r\n\r\n"))
          response @(client/get (url server))]
      (is (:error response))
      @(:done server)))

  (testing "chunked coding is case-insensitive"
    (let [server
          (one-response
            (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                 "Transfer-Encoding: Chunked\r\nConnection: close\r\n\r\n"
                 "2\r\nok\r\n0\r\n\r\n"))
          response @(client/get (url server) {:as :text})]
      (is (= 200 (:status response)))
      (is (= "ok" (:body response)))
      @(:done server)))

  (testing "invalid response lengths and transfer codings are rejected"
    (doseq [header ["Content-Length: -1" "Content-Length: +1"
                    "Content-Length: 2147483648"
                    "Transfer-Encoding: gzip"]]
      (let [server
            (one-response
              (str "HTTP/1.1 200 OK\r\n" header
                   "\r\nConnection: close\r\n\r\n"))
            response @(client/get (url server))]
        (is (:error response))
        @(:done server))))

  (testing "invalid status lines are rejected"
    (doseq [status-line ["GARBAGE 200 OK"
                         "HTTP/2.0 200 OK"
                         "HTTP/1.1 20 OK"
                         "HTTP/1.1 +20 OK"
                         "HTTP/1.1 2O0 OK"]]
      (let [server (one-response (str status-line "\r\n\r\n"))
            response @(client/get (url server))]
        (is (:error response))
        @(:done server)))))

(deftest trailers-complete-at-empty-line
  (let [trailer-sent (promise)
        finish-trailer (promise)
        server
        (raw-server
          (fn [^ServerSocket server]
            (with-open [socket (.accept server)]
              (read-request socket)
              (write! socket
                (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                     "Transfer-Encoding: chunked\r\nTrailer: X-Trailer\r\n\r\n"
                     "1\r\na\r\n0\r\nX-Trailer: yes\r\n"))
              (deliver trailer-sent true)
              @finish-trailer
              (write! socket "\r\n"))))
        response (client/get (url server) {:as :text})]
    @trailer-sent
    (is (= ::pending (deref response 100 ::pending)))
    (deliver finish-trailer true)
    (let [{:keys [body headers status]} @response]
      (is (= 200 status))
      (is (= "a" body))
      (is (= "yes" (:x-trailer headers))))
    @(:done server)))

(deftest redirects-to-get-drop-entity-headers
  (let [redirected-request (promise)
        server
        (raw-server
          (fn [^ServerSocket server]
            (with-open [socket (.accept server)]
              (read-request socket)
              (write! socket
                (str "HTTP/1.1 302 Found\r\nLocation: /target\r\n"
                     "Content-Length: 0\r\nConnection: close\r\n\r\n")))
            (with-open [socket (.accept server)]
              (deliver redirected-request (read-request socket))
              (write! socket
                (str "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                     "Connection: close\r\n\r\nok")))))
        response @(client/post (url server)
                    {:body "request body"
                     :headers {"cOnTeNt-LeNgTh" "12"
                               "CONTENT-TYPE" "text/plain"
                               "Content-Encoding" "gzip"}
                     :as :text})
        request (str/lower-case @redirected-request)]
    (is (= 200 (:status response)))
    (is (= "ok" (:body response)))
    (is (.startsWith request "get /target http/1.1\r\n"))
    (doseq [header ["content-length:" "content-type:"
                    "content-encoding:" "transfer-encoding:"]]
      (is (not (str/includes? request header))))
    @(:done server)))

(deftest request-framing
  (testing "chunked requests are rejected"
    (doseq [body [nil "body"]]
      (let [response @(client/request
                       {:url "http://127.0.0.1:9"
                        :method :post
                        :body body
                        :headers {"Transfer-Encoding" "chunked"}})]
        (is (instance? ProtocolException (:error response))))))

  (testing "bodyless requests require a valid zero content length"
    (doseq [content-length ["" "-1" "+1" "1" "18446744073709551616"]]
      (let [response @(client/get
                       "http://127.0.0.1:9"
                       {:headers {"Content-Length" content-length}})]
        (is (instance? ProtocolException (:error response))))))

  (testing "singleton request headers cannot be duplicated"
    (doseq [headers [{"Host" "one.test" "host" "two.test"}
                     {"Content-Length" "0" "content-length" "0"}
                     {"Host" ["one.test" "two.test"]}]]
      (let [response @(client/get "http://127.0.0.1:9" {:headers headers})]
        (is (instance? ProtocolException (:error response))))))

  (testing "request body length is computed by the client"
    (doseq [[body expected-length] [["abc" "3"] [[] "0"]]]
      (let [received (promise)
            server
            (raw-server
              (fn [^ServerSocket server]
                (with-open [socket (.accept server)]
                  (deliver received (read-request socket))
                  (write! socket
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))))
            response @(client/post (url server)
                       {:body body :headers {"content-length" "999"}})
            request (str/lower-case @received)]
        (is (= 200 (:status response)))
        (is (= 1 (count (re-seq #"content-length:" request))))
        (is (str/includes? request
              (str "content-length: " expected-length "\r\n")))
        @(:done server))))

  (testing "an explicit zero length is allowed without a body"
    (let [server (one-response
                   "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
          response @(client/get (url server)
                     {:headers {"Content-Length" "0"}})]
      (is (= 200 (:status response)))
      @(:done server))))

(deftest redirects-do-not-reapply-query-params
  (let [requests (atom [])
        server
        (raw-server
          (fn [^ServerSocket server]
            (doseq [response
                    [(str "HTTP/1.1 307 Temporary Redirect\r\n"
                          "Location: /middle?m=2\r\nContent-Length: 0\r\n"
                          "Connection: close\r\n\r\n")
                     (str "HTTP/1.1 307 Temporary Redirect\r\n"
                          "Location: /target?t=3\r\nContent-Length: 0\r\n"
                          "Connection: close\r\n\r\n")
                     "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"]]
              (with-open [socket (.accept server)]
                (swap! requests conj (read-request socket))
                (write! socket response)))))
        base-url (str (url server) "/start")
        response @(client/get base-url {:query-params {:q 1}})]
    (is (= 200 (:status response)))
    (is (= ["GET /start?q=1 HTTP/1.1"
            "GET /middle?m=2 HTTP/1.1"
            "GET /target?t=3 HTTP/1.1"]
          (mapv #(first (str/split-lines %)) @requests)))
    (is (= [(str base-url "?q=1") (str (url server) "/middle?m=2")]
          (:trace-redirects (:opts response))))
    @(:done server)))

(deftest redirects-respect-origin-and-method-boundaries
  (testing "cross-origin redirects drop credentials and explicit Host"
    (let [redirected-request (promise)
          target
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket (.accept server)]
                (deliver redirected-request (read-request socket))
                (write! socket
                  "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"))))
          source
          (one-response
            (str "HTTP/1.1 302 Found\r\nLocation: " (url target) "/target\r\n"
                 "Content-Length: 0\r\nConnection: close\r\n\r\n"))
          response @(client/get (url source)
                     {:basic-auth ["user" "secret"]
                      :headers {"Cookie" "session=secret"
                                "Host" "explicit.invalid"}
                      :as :text})
          request (str/lower-case @redirected-request)]
      (is (= 200 (:status response)))
      (is (not (str/includes? request "authorization:")))
      (is (not (str/includes? request "cookie:")))
      (is (str/includes? request (str "host: 127.0.0.1:" (:port target))))
      (is (not (str/includes? request "explicit.invalid")))
      @(:done source)
      @(:done target)))

  (testing "HEAD remains HEAD after redirect"
    (let [redirected-request (promise)
          server
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket (.accept server)]
                (read-request socket)
                (write! socket
                  "HTTP/1.1 302 Found\r\nLocation: /target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))
              (with-open [socket (.accept server)]
                (deliver redirected-request (read-request socket))
                (write! socket
                  "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))))
          response @(client/head (url server))]
      (is (= 200 (:status response)))
      (is (.startsWith ^String @redirected-request "HEAD /target HTTP/1.1\r\n"))
      @(:done server)))

  (testing "one-shot bodies are not silently replayed"
    (let [accepted (atom 0)
          server
          (raw-server
            (fn [^ServerSocket server]
              (.setSoTimeout server 300)
              (with-open [socket (.accept server)]
                (swap! accepted inc)
                (read-request socket)
                (write! socket
                  "HTTP/1.1 307 Temporary Redirect\r\nLocation: /target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))
              (try
                (with-open [socket (.accept server)]
                  (swap! accepted inc))
                (catch SocketTimeoutException _))))
          response @(client/post (url server)
                     {:body (ByteArrayInputStream. (.getBytes "body" "UTF-8"))})]
      (is (:error response))
      @(:done server)
      (is (= 1 @accepted)))))

(deftest activity-and-pool-limits-remain-live
  (testing "small periodic response chunks refresh idle timeout"
    (let [server
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket (.accept server)]
                (read-request socket)
                (write! socket
                  "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n")
                (doseq [b (.getBytes "hello" "UTF-8")]
                  (.write (.getOutputStream socket) (byte-array [(byte b)]))
                  (.flush (.getOutputStream socket))
                  (Thread/sleep 40)))))
          response @(client/get (url server) {:idle-timeout 100 :as :text})]
      (is (= "hello" (:body response)))
      @(:done server)))

  (testing "idle connections do not block another origin at the limit"
    (let [server-a
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket (.accept server)]
                (read-request socket)
                (write! socket "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\na")
                (.read (.getInputStream socket)))))
          server-b (one-response
                     "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nb")
          http-client (client/make-client {:max-connections 1})]
      (try
        (is (= "a" (:body @(client/get (url server-a)
                             {:client http-client :as :text}))))
        (is (= "b" (:body @(client/get (url server-b)
                             {:client http-client :as :text
                              :connect-timeout 300}))))
        @(:done server-a)
        @(:done server-b)
        (finally
          (.stop ^HttpClient http-client)
          ((:close server-a))
          ((:close server-b))))))

  (testing "queued requests retain their connect timeout"
    (let [release (promise)
          server-a
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket (.accept server)]
                (read-request socket)
                @release)))
          server-b
          (raw-server
            (fn [^ServerSocket server]
              (try
                (with-open [socket (.accept server)])
                (catch SocketException _))))
          http-client (client/make-client {:max-connections 1})]
      (try
        (client/get (url server-a) {:client http-client})
        (let [response (deref (client/get (url server-b)
                                {:client http-client :connect-timeout 100})
                         1000 ::timeout)]
          (is (not= ::timeout response))
          (is (:error response)))
        (finally
          (deliver release true)
          (.stop ^HttpClient http-client)
          ((:close server-a))
          ((:close server-b)))))))

(deftest nonpersistent-responses-are-not-reused
  (doseq [response-line ["HTTP/1.1 200 OK\r\nConnection: close"
                         "HTTP/1.0 200 OK"]]
    (let [accepted (atom 0)
          first-socket (atom nil)
          server
          (raw-server
            (fn [^ServerSocket server]
              (let [socket1 (.accept server)]
                (reset! first-socket socket1)
                (swap! accepted inc)
                (read-request socket1)
                (write! socket1
                  (str response-line "\r\nContent-Type: text/plain\r\n"
                       "Content-Length: 3\r\n\r\none"))
                (with-open [socket2 (.accept server)]
                  (swap! accepted inc)
                  (read-request socket2)
                  (write! socket2
                    (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                         "Content-Length: 3\r\nConnection: close\r\n\r\ntwo")))
                (.close socket1))))
          http-client (client/make-client {:max-connections 1})]
      (try
        (is (= "one" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (is (= "two" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (is (= 2 @accepted))
        (finally
          (.stop ^HttpClient http-client)
          (when-let [^Socket socket @first-socket]
            (.close socket))
          ((:close server)))))))

(deftest explicit-http10-keepalive-is-reused
  (let [accepted (atom 0)
        server
        (raw-server
          (fn [^ServerSocket server]
            (with-open [socket (.accept server)]
              (swap! accepted inc)
              (read-request socket)
              (write! socket
                (str "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n"
                     "Content-Length: 3\r\nConnection: keep-alive\r\n\r\none"))
              (read-request socket)
              (write! socket
                (str "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n"
                     "Content-Length: 3\r\nConnection: close\r\n\r\ntwo")))))
        http-client (client/make-client {:max-connections 1})]
    (try
      (is (= "one" (:body @(client/get (url server)
                             {:as :text :client http-client}))))
      (is (= "two" (:body @(client/get (url server)
                             {:as :text :client http-client}))))
      (is (= 1 @accepted))
      @(:done server)
      (finally
        (.stop ^HttpClient http-client)
        ((:close server))))))

(deftest invalid-http10-chunked-response-is-not-reused
  (let [accepted (atom 0)
        server
        (raw-server
          (fn [^ServerSocket server]
            (with-open [first (.accept server)]
              (swap! accepted inc)
              (read-request first)
              (write! first
                (str "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n"
                     "Transfer-Encoding: chunked\r\n"
                     "Connection: keep-alive\r\n\r\n3\r\none\r\n0\r\n\r\n")))
            (with-open [second (.accept server)]
              (swap! accepted inc)
              (read-request second)
              (write! second
                (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                     "Content-Length: 3\r\nConnection: close\r\n\r\ntwo")))))
        http-client (client/make-client {:max-connections 1})]
    (try
      (is (= "one" (:body @(client/get (url server)
                             {:as :text :client http-client}))))
      (is (= "two" (:body @(client/get (url server)
                             {:as :text :client http-client}))))
      (is (= 2 @accepted))
      @(:done server)
      (finally
        (.stop ^HttpClient http-client)
        ((:close server))))))

(deftest surplus-response-bytes-prevent-reuse
  (testing "bytes remaining after a response close the connection"
    (let [reused? (promise)
          server
          (raw-server
            (fn [^ServerSocket server]
              (let [socket (.accept server)]
                (try
                  (read-request socket)
                  (write! socket
                    "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\noneextra")
                  (.setSoTimeout socket 1000)
                  (let [request (try
                                  (read-request socket)
                                  (catch SocketTimeoutException _ "")
                                  (catch SocketException _ ""))
                        reused (not (str/blank? request))]
                    (deliver reused? reused)
                    (if reused
                      (write! socket
                        "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\ntwo")
                      (with-open [next-socket (.accept server)]
                        (read-request next-socket)
                        (write! next-socket
                          "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\ntwo"))))
                  (finally
                    (.close socket))))))
          http-client (client/make-client {:max-connections 1})]
      (try
        (is (= "one" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (is (= "two" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (is (false? @reused?))
        (finally
          (.stop ^HttpClient http-client)
          ((:close server))))))

  (testing "unexpected bytes close an already idle connection"
    (let [send-extra (promise)
          idle-closed (promise)
          server
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket (.accept server)]
                (read-request socket)
                (write! socket "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\none")
                @send-extra
                (write! socket "extra")
                (.setSoTimeout socket 1000)
                (try
                  (deliver idle-closed (= -1 (.read (.getInputStream socket))))
                  (catch SocketTimeoutException _
                    (deliver idle-closed false))
                  (catch SocketException _
                    (deliver idle-closed true))))
              (with-open [socket (.accept server)]
                (read-request socket)
                (write! socket
                  "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\ntwo"))))
          http-client (client/make-client {:max-connections 1})]
      (try
        (is (= "one" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (deliver send-extra true)
        (is (true? (deref idle-closed 1500 false)))
        (is (= "two" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (finally
          (.stop ^HttpClient http-client)
          ((:close server)))))))

(deftest stale-connections-retry-only-safe-methods
  (testing "GET is retried"
    (let [accepted (atom 0)
          server
          (raw-server
            (fn [^ServerSocket server]
              (with-open [socket1 (.accept server)]
                (swap! accepted inc)
                (read-request socket1)
                (write! socket1
                  (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                       "Content-Length: 3\r\n\r\none"))
                (read-request socket1))
              (with-open [socket2 (.accept server)]
                (swap! accepted inc)
                (read-request socket2)
                (write! socket2
                  (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                       "Content-Length: 5\r\nConnection: close\r\n\r\nretry")))))
          http-client (client/make-client {:max-connections 1})]
      (try
        (is (= "one" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (Thread/sleep 50)
        (is (= "retry" (:body @(client/get (url server)
                                 {:as :text :client http-client}))))
        (is (= 2 @accepted))
        (finally
          (.stop ^HttpClient http-client)
          ((:close server))))))

  (testing "POST is not retried"
    (let [accepted (atom 0)
          server
          (raw-server
            (fn [^ServerSocket server]
              (.setSoTimeout server 500)
              (with-open [socket1 (.accept server)]
                (swap! accepted inc)
                (read-request socket1)
                (write! socket1
                  (str "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                       "Content-Length: 3\r\n\r\none"))
                (read-request socket1))
              (try
                (with-open [socket2 (.accept server)]
                  (swap! accepted inc))
                (catch SocketTimeoutException _))))
          http-client (client/make-client {:max-connections 1})]
      (try
        (is (= "one" (:body @(client/get (url server)
                               {:as :text :client http-client}))))
        (Thread/sleep 50)
        (is (:error @(client/post (url server)
                      {:as :text :client http-client})))
        @(:done server)
        (is (= 1 @accepted))
        (finally
          (.stop ^HttpClient http-client)
          ((:close server)))))))

(deftest provider-errors-are-delivered-and-channels-closed
  (doseq [[label opts request-url]
          [["address finder"
            {:address-finder (fn [_] (throw (IllegalStateException. "address")))}
            "http://example.test"]
           ["SSL configurer"
            {:address-finder (fn [_] (InetSocketAddress. "127.0.0.1" 9))
             :ssl-configurer (fn [_ _] (throw (IllegalStateException. "ssl")))}
            "https://example.test"]
           ["channel factory"
            {:address-finder (fn [_] (InetSocketAddress. "127.0.0.1" 9))
             :channel-factory (fn [_] (throw (IllegalStateException. "channel")))}
            "http://example.test"]]]
    (testing label
      (let [http-client (client/make-client opts)]
        (try
          (is (:error @(client/get request-url {:client http-client})))
          (finally (.stop ^HttpClient http-client))))))

  (testing "partially configured channels are closed"
    (let [channel_ (atom nil)
          http-client
          (client/make-client
            {:address-finder
             (fn [_] (InetSocketAddress/createUnresolved "unresolved.test" 80))
             :channel-factory
             (fn [_]
               (let [channel (SocketChannel/open)]
                 (reset! channel_ channel)
                 channel))})]
      (try
        (is (:error @(client/get "http://example.test" {:client http-client})))
        (is (false? (.isOpen ^SocketChannel @channel_)))
        (finally (.stop ^HttpClient http-client)))))

  (testing "HTTPS proxy configures TLS for the proxy URI"
    (let [configured_ (atom nil)
          http-client
          (client/make-client
            {:address-finder (fn [_] (InetSocketAddress. "127.0.0.1" 9))
             :ssl-configurer
             (fn [_ uri]
               (reset! configured_ uri)
               (throw (IllegalStateException. "configured")))})]
      (try
        (is (:error @(client/get "http://target.test"
                      {:client http-client
                       :proxy-url "https://proxy.test:8443"})))
        (is (= "https://proxy.test:8443" (str @configured_)))
        (finally (.stop ^HttpClient http-client)))))

  (testing "header injection is rejected before connecting"
    (let [http-client
          (client/make-client
            {:address-finder (fn [_] (InetSocketAddress. "127.0.0.1" 9))})]
      (try
        (is (instance? IllegalArgumentException
              (:error @(client/get "http://example.test"
                        {:client http-client
                         :headers {"X-Test" "safe\r\nInjected: yes"}}))))
        (finally (.stop ^HttpClient http-client)))))

  (testing "unsupported proxy tunnels fail explicitly"
    (let [response @(client/get "https://target.test"
                     {:proxy-url "http://proxy.test" :tunnel? true})]
      (is (:error response))
      (is (str/includes? (str (:error response))
            "Proxy tunneling is not supported"))))
)
