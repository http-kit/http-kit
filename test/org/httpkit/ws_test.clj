(ns org.httpkit.ws-test
  (:use
   clojure.test
   [compojure
    [core    :only [defroutes GET]]
    [handler :only [site]]]
   org.httpkit.test-util
   org.httpkit.server)

  (:require
   [clojure.string       :as str]
   [http.async.client :as h]
   [ring.websocket    :as ws]
   [hato.websocket    :as hato])

  (:import
   [java.io ByteArrayOutputStream InputStream]
   [java.net Socket]
   [org.httpkit.ws WebSocketClient]
   [org.httpkit  SpecialHttpClient]
   [org.httpkit.server AsyncChannel]))

(defn ws-handler [req]
  (as-channel req
              {:on-close (fn [channel status]
                           (close-handler status))
               :on-receive (fn [channel msg]
                             (try
                               (let [{:keys [length times]} (read-string msg)]
                                 (doseq [_ (range 0 times)]
                                   (send! channel (subs const-string 0 length))))
                               (catch Exception e
                                 (println e)
                                 (send! channel msg))))}))

(defn ws-handler-async-client [req] ;; test with http.async.client, echo back
  (as-channel req
              {:on-receive (fn [channel mesg]
                             (send! channel mesg))}))

(defn binary-ws-handler [req]
  (as-channel req {:on-receive (fn [con data]
                                 (let [retdata (doto (aclone ^bytes data) (java.util.Arrays/sort))
                                       data (if (rand-nth [true false])
                                              (java.io.ByteArrayInputStream. retdata)
                                              retdata)]
                                   (send! con data)))}))

(defn ping-ws-handler [req]
  (as-channel req
              {:on-ping (fn [con data]
                          (send! con (str "ECHO: " (String. data "UTF-8"))))}))

(defn messg-order-handler [req]
  (let [mesg-idx (atom 0)]
    (as-channel req {:on-receive (fn [con mesg]
                                   (let [id (swap! mesg-idx inc)
                                         i (:id (read-string mesg))]
                                     (send! con (str (= id i)))))})))

(defn not-interleave-handler [req]
  (as-channel req
              {:on-receive (fn [con mesg]
                             (let [total (to-int mesg)]
                               (doall (pmap (fn [length idx]
                                              (let [length (+ length 1025)
                                                    c (char (+ (int \0) (rem length 30)))]
                                     ;; (Thread/sleep (rand-int (* 10 total)))
                                                (send! con (apply str (concat (take 4 (concat
                                                                                       (str idx)
                                                                                       (repeat \0)))
                                                                              (repeat length c))))))
                                            (repeatedly total (partial rand-int (* 1024 1024)))
                                            (range 10 1000)))))}))

(defroutes test-routes
  (GET "/ws" [] ws-handler)
  (GET "/echo" [] ws-handler-async-client)
  (GET "/http-async-client" [] ws-handler-async-client)
  (GET "/binary" [] binary-ws-handler)
  (GET "/interleaved" [] not-interleave-handler)
  (GET "/order" [] messg-order-handler)
  (GET "/ping-pong" [] ping-ws-handler)
  (GET "/close-reason" []
    (fn [req]
      (as-channel req
        {:on-open (fn [^AsyncChannel ch]
                    (.serverClose ch 1000 "bye"))}))))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (site test-routes) {:port 4348})]
                        (try (f) (finally (server))))))

(comment (def server (run-server (site test-routes) {:port 4348}))
         (def client1 (WebSocketClient. "ws://localhost:4348/ws")))

(def ^:private valid-handshake
  {:request-method :get
   :protocol "HTTP/1.1"
   :headers
   {"upgrade"               "websocket"
    "connection"            "Upgrade"
    "sec-websocket-version" "13"
    "sec-websocket-key"     "dGhlIHNhbXBsZSBub25jZQ=="}})

(deftest test-websocket-handshake-validation
  (let [expected (sec-websocket-accept
                   (get-in valid-handshake [:headers "sec-websocket-key"]))]
    (is (= expected (websocket-handshake-check valid-handshake)))
    (is (= expected
          (websocket-handshake-check
            (-> valid-handshake
              (assoc-in [:headers "upgrade"] "WebSocket")
              (assoc-in [:headers "connection"] "keep-alive, UpGrAdE")))))
    (doseq [[description request]
            [["non-GET method"        (assoc valid-handshake :request-method :post)]
             ["non-HTTP/1.1 protocol" (assoc valid-handshake :protocol "HTTP/1.0")]
             ["missing Upgrade"       (update valid-handshake :headers dissoc "upgrade")]
             ["wrong Upgrade"         (assoc-in valid-handshake [:headers "upgrade"] "h2c")]
             ["missing Connection"    (update valid-handshake :headers dissoc "connection")]
             ["wrong Connection"      (assoc-in valid-handshake [:headers "connection"] "keep-alive")]
             ["missing version"       (update valid-handshake :headers dissoc "sec-websocket-version")]
             ["wrong version"         (assoc-in valid-handshake [:headers "sec-websocket-version"] "12")]
             ["missing key"           (update valid-handshake :headers dissoc "sec-websocket-key")]
             ["malformed key"         (assoc-in valid-handshake [:headers "sec-websocket-key"] "not base64")]
             ["non-canonical key"     (assoc-in valid-handshake [:headers "sec-websocket-key"] "dGhlIHNhbXBsZSBub25jZQ")]
             ["wrong-length key"      (assoc-in valid-handshake [:headers "sec-websocket-key"] "dG9vIHNob3J0")]]]
      (testing description
        (is (nil? (websocket-handshake-check request)))))))

(defn- read-http-head [^InputStream in]
  (loop [bytes []]
    (let [b (.read in)
          bytes (conj bytes b)]
      (when (= -1 b)
        (throw (java.io.EOFException. "EOF during WebSocket handshake")))
      (if (= [13 10 13 10] (take-last 4 bytes))
        (String. (byte-array (map unchecked-byte bytes)) "UTF-8")
        (recur bytes)))))

(defn- raw-websocket
  ([] (raw-websocket 4348 "/echo"))
  ([path] (raw-websocket 4348 path))
  ([port path]
  (let [socket (doto (Socket. "localhost" (int port)) (.setSoTimeout 2000))
        out    (.getOutputStream socket)
        request
        (str "GET " path " HTTP/1.1\r\n"
          "Host: localhost:" port "\r\n"
          "Upgrade: websocket\r\n"
          "Connection: Upgrade\r\n"
          "Sec-WebSocket-Version: 13\r\n"
          "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")]
    (.write out (.getBytes request "UTF-8"))
    (.flush out)
    (let [head (read-http-head (.getInputStream socket))]
      (when-not (str/includes? head " 101 ")
        (.close socket)
        (throw (ex-info "WebSocket handshake failed" {:response head}))))
    socket)))

(deftest invalid-upgrades-return-http-errors
  (with-open [socket (doto (Socket. "localhost" 4348) (.setSoTimeout 2000))]
    (let [out (.getOutputStream socket)]
      (.write out (.getBytes
                    (str "GET /echo HTTP/1.1\r\nHost: localhost\r\n"
                      "Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
                    "UTF-8"))
      (.flush out)
      (is (str/includes? (read-http-head (.getInputStream socket))
            "HTTP/1.1 400")))))

(defn- masked-frame [final? rsv opcode payload]
  (let [^bytes payload (if (string? payload) (.getBytes ^String payload "UTF-8") payload)
        length         (alength payload)
        mask           (byte-array [1 2 3 4])
        out            (ByteArrayOutputStream.)]
    (.write out (bit-or (if final? 0x80 0) rsv opcode))
    (cond
      (<= length 125)
      (.write out (bit-or 0x80 length))

      (<= length 0xffff)
      (do (.write out (bit-or 0x80 126))
          (.write out (bit-shift-right length 8))
          (.write out length))

      :else
      (throw (IllegalArgumentException. "test frame is too large")))
    (.write out mask)
    (dotimes [idx length]
      (.write out
        (bit-xor (bit-and 0xff (aget payload idx))
          (bit-and 0xff (aget mask (mod idx 4))))))
    (.toByteArray out)))

(defn- write-frames! [^Socket socket frames]
  (let [out (.getOutputStream socket)]
    (doseq [frame frames] (.write out ^bytes frame))
    (.flush out)))

(defn- max-ws-frames [opcode lengths]
  (let [last-idx (dec (count lengths))]
    (map-indexed
      (fn [idx length]
        (masked-frame (= idx last-idx) 0 (if (zero? idx) opcode 0)
          (byte-array length (repeat (byte 97)))))
      lengths)))

(defn- read-byte! [^InputStream in]
  (let [b (.read in)]
    (when (= -1 b) (throw (java.io.EOFException. "EOF during WebSocket frame")))
    b))

(defn- read-frame [^Socket socket]
  (let [in     (.getInputStream socket)
        first  (read-byte! in)
        second (read-byte! in)
        length-code (bit-and second 0x7f)
        length (case length-code
                 126 (+ (bit-shift-left (read-byte! in) 8) (read-byte! in))
                 127 (throw (IllegalArgumentException. "test frame is too large"))
                 length-code)
        body (byte-array length)]
    (loop [offset 0]
      (when (< offset length)
        (let [read (.read in body offset (- length offset))]
          (when (= -1 read) (throw (java.io.EOFException. "EOF during WebSocket payload")))
          (recur (+ offset read)))))
    {:final? (not (zero? (bit-and first 0x80)))
     :opcode (bit-and first 0x0f)
     :body body}))

(defn- close-status [{:keys [body]}]
  (bit-or (bit-shift-left (bit-and 0xff (aget ^bytes body 0)) 8)
    (bit-and 0xff (aget ^bytes body 1))))

(defn- with-max-ws-server [max-ws f]
  (let [server (run-server (site test-routes)
                 {:port 0 :max-ws max-ws :warn-logger (fn [_ _])})]
    (try
      (f (:local-port (meta server)))
      (finally (server)))))

(deftest test-websocket-max-message-size
  (doseq [[max-ws cases]
          [[100 [[[100] true] [[101] false]]]
           [200 [[[200] true] [[201] false]
                 [[100 100] true] [[100 101] false]
                 [[126 74] true] [[126 75] false]]]]]
    (with-max-ws-server max-ws
      (fn [port]
        (doseq [opcode [0x1 0x2]
                [lengths accepted?] cases]
          (testing (str "max-ws=" max-ws ", opcode=" opcode ", lengths=" lengths)
            (with-open [^Socket socket (raw-websocket port "/echo")]
              (write-frames! socket (max-ws-frames opcode lengths))
              (let [frame (read-frame socket)]
                (if accepted?
                  (do
                    (is (= opcode (:opcode frame)))
                    (is (= max-ws (alength ^bytes (:body frame)))))
                  (do
                    (is (= 0x8 (:opcode frame)))
                    (is (= 1009 (close-status frame))))))))))))

  (with-max-ws-server 4
    (fn [port]
      (with-open [^Socket socket (raw-websocket port "/echo")]
        (write-frames! socket [(masked-frame true 0 0x9 "hello")])
        (let [pong (read-frame socket)]
          (is (= 0xa (:opcode pong)))
          (is (= "hello" (String. ^bytes (:body pong) "UTF-8"))))))))

(deftest server-close-preserves-reason
  (with-open [^Socket socket (raw-websocket "/close-reason")]
    (let [{:keys [opcode body]} (read-frame socket)]
      (is (= 0x8 opcode))
      (is (= 1000 (bit-or (bit-shift-left (bit-and 0xff (aget ^bytes body 0)) 8)
                    (bit-and 0xff (aget ^bytes body 1)))))
      (is (= "bye" (String. ^bytes body 2 (- (alength ^bytes body) 2) "UTF-8"))))))

(defn- server-closes-after? [frames]
  (with-open [^Socket socket (raw-websocket)]
    (write-frames! socket frames)
    (let [close-frame (read-frame socket)]
      (and (= 0x8 (:opcode close-frame))
           (= -1 (.read (.getInputStream socket)))))))

(deftest test-interleaved-websocket-control-frames
  (with-open [^Socket socket (raw-websocket)]
    (write-frames! socket
      [(masked-frame false 0 0x1 "hel")
       (masked-frame true  0 0x9 "ping")
       (masked-frame true  0 0xa "ignored")
       (masked-frame true  0 0x0 "lo")])
    (let [pong (read-frame socket)
          echo (read-frame socket)]
      (is (= 0xa (:opcode pong)))
      (is (= "ping" (String. ^bytes (:body pong) "UTF-8")))
      (is (= 0x1 (:opcode echo)))
      (is (= "hello" (String. ^bytes (:body echo) "UTF-8")))))

  (with-open [^Socket socket (raw-websocket)]
    (write-frames! socket
      [(masked-frame false 0 0x1 "unfinished")
       (masked-frame true  0 0x8 (byte-array 0))])
    (is (= 0x8 (:opcode (read-frame socket))))))

(deftest test-invalid-websocket-frame-sequences
  (doseq [[description frames]
          [["RSV bit"
            [(masked-frame true 0x40 0x1 "bad")]]
           ["fragmented control frame"
            [(masked-frame false 0 0x9 "bad")]]
           ["oversized control frame"
            [(masked-frame true 0 0x9 (byte-array 126))]]
           ["unexpected continuation"
            [(masked-frame true 0 0x0 "bad")]]
           ["new data frame during fragmented message"
            [(masked-frame false 0 0x1 "open")
             (masked-frame true  0 0x2 "bad")]]
           ["invalid UTF-8 text"
            [(masked-frame true 0 0x1 (byte-array [(unchecked-byte 0xc3) 0x28]))]]
           ["one-byte close payload"
            [(masked-frame true 0 0x8 (byte-array [0]))]]]]
    (testing description
      (is (server-closes-after? frames)))))

(deftest test-websocket
  (doseq [_ (range 1 4)]
    (let [client (WebSocketClient. "ws://localhost:4348/ws")]
      (doseq [_ (range 0 10)]
        (let [length (rand-int (* 4 1024 1024))
              times (rand-int 10)]
          ;; ask for a given length, make sure server understand it
          (.sendMessage client (pr-str {:length length :times times}))
          (doseq [_ (range 0 times)]
            (is (= length (count (.getMessage client)))))))
      (.close client) ;; server's closeFrame response is checked
      ;; see test_util.clj
      (check-on-close-called))))

(deftest test-websocket-fragmented
  (let [client (WebSocketClient. "ws://localhost:4348/ws")]
    (doseq [_ (range 0 10)]
      (let [length (min 100 (rand-int 10024))
            sent (pr-str {:length length :times 2
                          :text (subs const-string 0 length)})]
        ;; ask for a given length, make sure server understand it
        (.sendFragmentedMesg client sent)
        (doseq [_ (range 0 2)]
          (let [r (.getMessage client)]
            (when (not= (count r) length)
              (println (str "sent:\n" sent
                            "\n---------------------------------"
                            "\nreceive:\n" r))
              (is false))))
        (let [d (subs const-string 0 120)]
          (is (= d (.ping client d)))
          (.pong client d))))
    (.close client)))

(deftest test-websocket-ping-handler
  (let [client (WebSocketClient. "ws://localhost:4348/ping-pong")]
    (.ping client "TEST")
    (is (= "ECHO: TEST" (.getMessage client)))
    (.close client)))

(deftest test-tcp-segmented-frame-does-right  ; issue #47
  (let [data (slurp "test/ws_unmask_bug_47.txt") ; 65 data, segement sure, since receive buffer is 64K
        data_3 (str data data data)
        client (WebSocketClient. "ws://localhost:4348/echo")]
    (dotimes [_ 3]
      (.sendFragmentedMesg client data_3 3)
      (is (= data_3 (.getMessage client)))
      (.sendMessage client data)
      (is (= data (.getMessage client))))))

;; client can sent a byte a time
;; https://github.com/http-kit/http-kit/issues/80
(deftest test-slow-client
  (is (SpecialHttpClient/slowWebSocketClient "ws://localhost:4348/echo")))

(deftest test-binary-frame
  (let [client (WebSocketClient. "ws://localhost:4348/binary")]
    (dotimes [_ 5]
      (let [length (min 1024 (rand-int 10024))
            data (byte-array length (take length (repeatedly #(byte (rand-int 126)))))
            ^bytes sorted-data (doto (aclone data) (java.util.Arrays/sort))]
        (.sendBinaryData client data)
        (let [^bytes output (.getMessage client)]
          (is (java.util.Arrays/equals sorted-data output)))))
    (.close client)))

;;; make sure Message ordering is guaranteed
(deftest test-message-executed-in-order
  (doseq [_ (range 1 5)]
    (let [client (WebSocketClient. "ws://localhost:4348/order")]
      (doseq [id (range 1 10)]
        (.sendMessage client (pr-str {:id id}))
        (is (= "true" (.getMessage client))))
      (.close client)))
  (doseq [_ (range 1 5)]
    (let [client (WebSocketClient. "ws://localhost:4348/order")]
      ;; 10 concurrent message
      (doseq [id (range 1 10)]
        (.sendMessage client (pr-str {:id id})))
      (doseq [_ (range 1 10)]
        (is (= "true" (.getMessage client))))
      (.close client))))

(deftest test-message-are-not-interleaved
  ;; TODO when length is large, http-kit seem to drop some buffer.
  ;; The problem remains even if writen is done by a signle Thread
  ;; A bug of http-kit or JVM?
  ;; Not a issue for http, But maybe a issue for websocket:
  ;; If write many large chunks of messages to client using many threads concurrenly
  (let [client (WebSocketClient. "ws://localhost:4348/interleaved")
        length 10]
    (.sendMessage client (str length))
    (doseq [i (range 0 length)]
      (let [mesg ^String (.getMessage client)]
        (if mesg
          (let [idx (.substring mesg 0 4)
                mesg (.substring mesg 4)
                ch (first mesg)]
            (is (every? (fn [c] (= c ch)) mesg)))
          ;; fail
          (is (> (count mesg) 1024)))))
    (.close client)))

(deftest test-with-http.async.client
  (with-open [client (h/create-client)]
    (let [latch         (promise)
          received-msg_ (atom nil)
          ws
          (h/websocket client "ws://localhost:4348/http-async-client"
            :text  (fn [con msg]    (reset! received-msg_ msg) (deliver latch true))
            :close (fn [con status] #_(println "close:"  con status))
            :open  (fn [con]        #_(println "opened:" con)))]

      ;; (h/send ws :byte (byte-array 10)) not implemented yet
      (let [msg "testing12"] (h/send ws :text msg) @latch (is (= msg @received-msg_)))
      (h/close ws))))

(defn- buf->str [buffer]
  (let [bs (byte-array (.capacity buffer))]
    (doto buffer .mark (.get bs) .reset)
    (String. bs)))

(deftest test-ring-websocket-handlers
  (when-not @#'org.httpkit.server/no-ring-websockets?
    (let [log_ (atom [])
          log+ (fn [x] (swap! log_ conj x))
          handler
          (constantly
            {::ws/protocol "test"
             ::ws/listener
             {:on-open    (fn [sock]           (log+ [:server/open]) (ws/send sock "hello"))
              :on-ping    (fn [sock bb-data]   (log+ [:server/ping (buf->str bb-data)]) (Thread/sleep 50) (ws/pong sock bb-data))
              :on-pong    (fn [_    bb-data]   (log+ [:server/pong (buf->str bb-data)]))
              :on-message (fn [_ msg]          (log+ [:server/message msg]))
              :on-close   (fn [_ code reason]  (log+ [:server/close code reason]))}})

          server (run-server handler {:port 9092})]

      (try
        (let [ws
              @(hato/websocket "ws://localhost:9092/"
                 {:subprotocols ["test"]
                  :on-open    (fn [_]             (log+ [:client/open]))
                  :on-ping    (fn [_ bb-data]     (log+ [:client/ping (buf->str bb-data)]))
                  :on-pong    (fn [_ bb-data]     (log+ [:client/pong (buf->str bb-data)]))
                  :on-message (fn [_ msg _close?] (log+ [:client/message (str msg)]))
                  :on-close   (fn [_ code reason] (log+ [:client/close code reason]))})]

          (is (= "test" (.getSubprotocol ^java.net.http.WebSocket ws)))
          (Thread/sleep 100) @(hato/send!  ws "world")
          (Thread/sleep 100) @(hato/ping!  ws (java.nio.ByteBuffer/wrap (.getBytes "foo")))
          (Thread/sleep 100) @(hato/close! ws 1000 "normal closure")
          (Thread/sleep 100))

        (finally (server)))

      (is (= @log_
            [[:server/open]
             [:client/open]
             [:client/message "hello"]
             [:server/message "world"]
             [:server/ping "foo"]
             [:client/pong "foo"]
             [:server/close 1000 "normal closure"]
             [:client/close 1000 "normal closure"]])))))

;; ;; test many times, and connect result
;; ;; rm /tmp/test_results&& ./scripts/javac with-test && for i in {1..100}; do lein test org.httpkit.ws-test >> /tmp/test_results; done
