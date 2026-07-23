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

  (testing "bodyless responses ignore framing headers"
    (doseq [[method status] [[:head 200] [:get 204] [:get 205] [:get 304]]]
      (let [release (promise)
            server
            (raw-server
              (fn [^ServerSocket server]
                (with-open [socket (.accept server)]
                  (read-request socket)
                  (write! socket
                    (str "HTTP/1.1 " status " Bodyless\r\n"
                         "Content-Length: 999\r\nTransfer-Encoding: invalid\r\n\r\n"))
                  @release)))
            request ((case method :head client/head client/get) (url server))
            response (deref request 500 ::timeout)]
        (deliver release true)
        (is (not= ::timeout response))
        (is (= status (:status response)))
        @(:done server))))

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
                               "Content-Encoding" "gzip"
                               "transfer-ENCODING" "chunked"}
                     :as :text})
        request (str/lower-case @redirected-request)]
    (is (= 200 (:status response)))
    (is (= "ok" (:body response)))
    (is (.startsWith request "get /target http/1.1\r\n"))
    (doseq [header ["content-length:" "content-type:"
                    "content-encoding:" "transfer-encoding:"]]
      (is (not (str/includes? request header))))
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
