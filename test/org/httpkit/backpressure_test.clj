(ns org.httpkit.backpressure-test
  "Outbound backpressure: a peer that stops reading must not be able to
  exhaust the server's heap.

  Before this existed, `toWrites` was an unbounded LinkedList with no signal:
  one non-reading client took 1.36 GB in 8 seconds while `send!` returned true
  on all 20 000 calls and the channel still reported open."
  (:require [clojure.test :refer [deftest testing is]]
            [org.httpkit.server :as hk]
            [ring.websocket :as ws]
            [ring.websocket.protocols :as wsp])
  (:import [java.net InetSocketAddress Socket SocketTimeoutException]
           [java.io OutputStreamWriter BufferedWriter]
           [java.nio ByteBuffer]
           [java.nio.channels ServerSocketChannel]))

(defn- slurp-bytes [url]
  (let [c (java.net.http.HttpClient/newHttpClient)
        r (.send c (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                       .GET .build)
                 (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
    (.body r)))

(defn- ws-handshake-client
  "Completes a WebSocket handshake and then never reads."
  ([port] (ws-handshake-client port nil))
  ([port receive-buffer-size]
   (let [^Socket sock (Socket.)]
     (when receive-buffer-size (.setReceiveBufferSize sock receive-buffer-size))
     (.connect sock (InetSocketAddress. "localhost" (int port)))
     (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock)))]
       (.write w (str "GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                      "Sec-WebSocket-Version: 13\r\n\r\n"))
       (.flush w))
     sock)))

(defn- ring-websocket-server []
  (let [opened (promise)
        stopped (hk/run-server
                 (constantly
                  {::ws/listener
                   {:on-open (fn [socket] (deliver opened socket))}})
                 {:port 0 :max-queued-bytes 0})]
    {:opened opened
     :stop stopped
     :port (:local-port (meta stopped))}))

(defn- await-result
  "Dereference a promise for at most ten seconds without retaining :timeout."
  [p]
  (deref p 10000 ::timeout))

(defn- non-reading-client
  "A client that completes a request and then never reads the response, so the
  kernel buffer fills, TCP zero-windows, and everything after that is the
  server's problem."
  [port]
  (let [^Socket sock (Socket. "localhost" (int port))
        w (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock)))]
    (.write w "GET / HTTP/1.1\r\nHost: localhost\r\nAccept: */*\r\n\r\n")
    (.flush w)
    sock))

(defn- with-stalled-connection
  "Run `f` with a channel whose peer is not reading. `opts` go to run-server."
  [opts f]
  (let [chans (atom #{})
        handler (fn [req]
                  (hk/as-channel req
                    {:on-open (fn [ch]
                                (swap! chans conj ch)
                                (hk/send! ch {:status 200
                                              :headers {"Content-Type" "text/plain"}
                                              :body "start\n"}
                                          false))
                     :on-close (fn [ch _] (swap! chans disj ch))}))
        ;; :port 0 and read back the real one -- the repo idiom. A fixed port
        ;; in the ephemeral range races anything else on the machine.
        stop (hk/run-server handler (assoc opts :port 0))
        port (:local-port (meta stop))
        sock (non-reading-client port)]
    (try
      (loop [n 0] (when (and (empty? @chans) (< n 100))
                    (Thread/sleep 20) (recur (inc n))))
      (is (seq @chans) "the connection was never established")
      (f (first @chans))
      (finally (.close ^Socket sock) (stop)))))

(defn- blast!
  "Push 64 KiB messages until `stop?`, returning stats."
  [ch limit stop?]
  (let [payload (apply str (repeat 65536 \x))]
    (loop [i 0 unwritable 0 first-unwritable nil]
      (if (or (>= i limit) (stop? i))
        {:sends i :unwritable unwritable :first-unwritable first-unwritable}
        (do (hk/send! ch payload false)
            (let [w (hk/writable? ch)]
              (recur (inc i)
                     (if w unwritable (inc unwritable))
                     (or first-unwritable (when-not w i)))))))))

(deftest writable-goes-false-when-the-peer-falls-behind
  (testing "the predicate, not a status returned from send"
    ;; Every comparable server exposes a writability PREDICATE and leaves send
    ;; alone: Netty's Channel.isWritable(), Servlet 3.1's isReady(), Node's
    ;; boolean. None returns a backpressure status from the send call itself.
    (with-stalled-connection
      {:queue-high-water-bytes (* 256 1024)
       :queue-low-water-bytes (* 128 1024)
       :max-queued-bytes 0}
      (fn [ch]
        (is (hk/writable? ch) "an idle connection is writable")
        (let [{:keys [unwritable first-unwritable]} (blast! ch 400 (constantly false))]
          (is (pos? unwritable) "backpressure was never reported")
          ;; Not asserted tighter: the kernel socket buffer absorbs megabytes
          ;; before `toWrites` grows at all, and how much is a property of the
          ;; machine, not of this code.
          (is (< first-unwritable 200)
              "must be reported long before memory is the problem")
          (is (hk/open? ch) "the signal alone must NOT close the connection"))))))

(deftest the-two-marks-give-hysteresis
  (testing "writability is restored at the LOW mark, not the high one"
    ;; With a single threshold a connection parked at the boundary flips state
    ;; on every write, so an application that pauses on the signal resumes
    ;; immediately and pauses again. Netty's WriteBufferWaterMark exists for
    ;; exactly this; Envoy independently chose low = high/2.
    (with-stalled-connection
      {:queue-high-water-bytes (* 256 1024)
       :queue-low-water-bytes (* 8 1024)
       :max-queued-bytes 0}
      (fn [ch]
        (blast! ch 400 (fn [_] (not (hk/writable? ch))))
        (is (not (hk/writable? ch)))
        ;; Still unwritable while the backlog is between the marks.
        (is (> (hk/queued-bytes ch) (* 8 1024)))
        (is (not (hk/writable? ch))
            "must stay unwritable until the LOW mark, not recover at the high one")))))

(deftest on-writable-fires-when-the-queue-drains
  (testing "a push resume signal, as every precedent has"
    ;; queued-bytes alone would force the application to poll. Netty fires
    ;; channelWritabilityChanged, Node fires 'drain', Servlet fires
    ;; onWritePossible.
    (let [fired (atom 0)
          chans (atom #{})
        stop (hk/run-server
                (fn [req] (hk/as-channel req
                            {:on-open (fn [ch]
                                        (swap! chans conj ch)
                                        (hk/send! ch {:status 200 :body "x\n"} false))
                             :on-close (fn [ch _] (swap! chans disj ch))}))
                {:port 0 :queue-high-water-bytes (* 64 1024)
                 :queue-low-water-bytes (* 32 1024) :max-queued-bytes 0})
          port (:local-port (meta stop))
          sock (Socket. "localhost" (int port))]
      (try
        (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock)))]
          (.write w "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n") (.flush w))
        (loop [n 0] (when (and (empty? @chans) (< n 100)) (Thread/sleep 20) (recur (inc n))))
        (let [ch (first @chans)]
          ;; Fill until unwritable while NOT reading...
          (blast! ch 600 (fn [_] (not (hk/writable? ch))))
          (is (not (hk/writable? ch)) "never went unwritable, so nothing to restore")
          (is (zero? @fired) "must not fire before writability was lost")
          (hk/on-writable ch (fn [] (swap! fired inc)))
          ;; ...then drain, and the transition must be announced.
          (.setSoTimeout sock 250)
          (let [in (.getInputStream sock) buf (byte-array 65536)
                deadline (+ (System/currentTimeMillis) 10000)]
            (loop [] (when (and (zero? @fired) (< (System/currentTimeMillis) deadline))
                       (try (.read in buf)
                            (catch SocketTimeoutException _ 0))
                       (recur))))
          (is (pos? @fired) "on-writable never fired after the queue drained")
          (is (hk/writable? ch))

          ;; The continuation is one-shot. Let a second restoration happen
          ;; with nobody waiting, then register after that edge: registration
          ;; must run immediately instead of losing the wakeup.
          (blast! ch 600 (fn [_] (not (hk/writable? ch))))
          (is (not (hk/writable? ch)) "second cycle never went unwritable")
          (let [in (.getInputStream sock) buf (byte-array 65536)
                deadline (+ (System/currentTimeMillis) 10000)]
            (loop [] (when (and (not (hk/writable? ch))
                                (< (System/currentTimeMillis) deadline))
                       (try (.read in buf)
                            (catch SocketTimeoutException _ 0))
                       (recur))))
          (is (hk/writable? ch) "second cycle never restored")
          (is (= 1 @fired) "the first continuation must not persist")
          (hk/on-writable ch (fn [] (swap! fired inc)))
          (loop [n 0]
            (when (and (= 1 @fired) (< n 100))
              (Thread/sleep 5)
              (recur (inc n))))
          (is (= 2 @fired)
              "registration after a missed restore edge must run promptly"))
        (finally (.close sock) (stop))))))

(deftest watermark-options-are-normalized-before-server-construction
  (testing "the documented one-option disable works"
    (let [stop (hk/run-server (constantly {:status 200 :body "ok"})
                              {:port 0 :queue-high-water-bytes 0})]
      (try
        (is (pos? (:local-port (meta stop))))
        (finally (stop)))))

  (testing "invalid marks fail before a server channel is opened"
    (let [factory-calls (atom 0)]
      (is (thrown? IllegalArgumentException
                   (hk/run-server
                    (constantly {:status 200 :body "ok"})
                    {:port 0
                     :queue-high-water-bytes 1
                     :queue-low-water-bytes 2
                     :channel-factory
                     (fn [_]
                       (swap! factory-calls inc)
                       (ServerSocketChannel/open))})))
      (is (zero? @factory-calls)
          "validation must precede opening or binding server resources"))))

(deftest queued-bytes-tracks-the-backlog
  (testing "queued-bytes exposes the backlog, so a caller can resume"
    (with-stalled-connection
      {:queue-high-water-bytes 0 :queue-low-water-bytes 0 :max-queued-bytes 0}
      (fn [ch]
        (is (zero? (hk/queued-bytes ch)) "nothing queued before we send")
        ;; Send until a backlog appears rather than a fixed count: the kernel
        ;; socket buffer absorbs megabytes first, and how many is a property of
        ;; the machine's tcp_wmem, not of this code.
        (let [deadline (+ (System/currentTimeMillis) 10000)]
          (loop []
            (when (and (zero? (hk/queued-bytes ch))
                       (< (System/currentTimeMillis) deadline)
                       (hk/open? ch))
              (blast! ch 10 (constantly false))
              (recur))))
        (is (pos? (hk/queued-bytes ch))
            "a stalled peer must show a backlog")))))

(deftest a-large-response-to-a-HEALTHY-client-is-not-truncated
  ;; The regression that made the ceiling unshippable on by default:
  ;; HttpUtils.bodyBuffer materialises a whole body into ONE ByteBuffer, so a
  ;; large download arrives at tryWrite as a single enormous enqueue -- from a
  ;; client that is reading perfectly. A size ceiling cannot tell that apart
  ;; from a stalled peer, so it must not be on by default.
  (let [body (byte-array (* 12 1024 1024))
        stop (hk/run-server (fn [_] {:status 200 :body body}) {:port 0})
        port (:local-port (meta stop))]
    (try
      (let [got (slurp-bytes (str "http://localhost:" port "/"))]
        (is (= (count body) (count got))
            "an ordinary large download must not be truncated under defaults"))
      (finally (stop))))

  (testing "and an explicit ceiling below the body size DOES cut it"
    ;; Stated so the tradeoff is recorded rather than discovered: enabling the
    ;; ceiling is only safe when responses are small and incremental.
    (let [body (byte-array (* 12 1024 1024))
          stop (hk/run-server (fn [_] {:status 200 :body body})
                              {:port 0 :max-queued-bytes (* 1 1024 1024)})
          port (:local-port (meta stop))]
      (try
        (let [got (try (slurp-bytes (str "http://localhost:" port "/"))
                       (catch Exception _ (byte-array 0)))]
          (is (< (count got) (count body))))
        (finally (stop))))))

(deftest websocket-goes-through-the-same-accounting
  ;; The claim is that HTTP streaming and WebSocket share the write path. Every
  ;; other test here is HTTP, so this is the one that checks the other half.
  (let [chans (atom #{})
        stop (hk/run-server
              (fn [req] (hk/as-channel req {:on-open (fn [ch] (swap! chans conj ch))
                                            :on-close (fn [ch _] (swap! chans disj ch))}))
              {:port 0 :queue-high-water-bytes (* 256 1024)
               :queue-low-water-bytes (* 128 1024) :max-queued-bytes 0})
        port (:local-port (meta stop))
        sock (ws-handshake-client port)]
    (try
      (loop [n 0] (when (and (empty? @chans) (< n 100)) (Thread/sleep 20) (recur (inc n))))
      (is (seq @chans) "websocket never connected")
      (let [ch (first @chans)
            {:keys [unwritable]} (blast! ch 400 (fn [_] (not (hk/open? ch))))]
        (is (pos? unwritable)
            "a stalled websocket peer must go unwritable, same as HTTP"))
      (finally (.close ^Socket sock) (stop)))))

(deftest ring-async-send-succeeds-only-after-queued-bytes-are-written
  (when-not @#'org.httpkit.server/no-ring-websockets?
    (let [{:keys [opened stop port]} (ring-websocket-server)
          sock (ws-handshake-client port 1024)]
      (try
        (let [socket (await-result opened)]
          (is (not= ::timeout socket) "Ring websocket never opened")
          (when (not= ::timeout socket)
            (is (satisfies? wsp/AsyncSocket socket))
            (let [completed (promise)
                  successes (atom 0)
                  failures (atom 0)
                  payload (ByteBuffer/wrap (byte-array (* 16 1024 1024)))]
              (ws/send socket payload
                       #(do (swap! successes inc) (deliver completed true))
                       (fn [_] (swap! failures inc)))
              (is (= ::timeout (deref completed 100 ::timeout))
                  "completion must not mean merely accepted into http-kit's queue")

              ;; Once the peer drains, the selector writes the queued tail and
              ;; the callback must follow that last byte, exactly once.
              (.setSoTimeout ^Socket sock 250)
              (let [^java.io.InputStream in (.getInputStream ^Socket sock)
                    buf (byte-array 65536)
                    deadline (+ (System/currentTimeMillis) 10000)]
                (loop []
                  (when (and (not (realized? completed))
                             (< (System/currentTimeMillis) deadline))
                    (try (.read in buf)
                         (catch SocketTimeoutException _ 0))
                    (recur))))
              (is (= true (await-result completed))
                  "success callback never followed the drained write")
              (Thread/sleep 50)
              (is (= 1 @successes))
              (is (zero? @failures))

              (let [invalid-failed (promise)]
                (ws/send socket 42
                         #(deliver invalid-failed :unexpected-success)
                         #(deliver invalid-failed %))
                (is (instance? IllegalArgumentException
                               (await-result invalid-failed))
                    "async validation errors must reach fail, not escape")))))
        (finally (.close ^Socket sock) (stop))))))

(deftest ring-async-send-fails-when-a-queued-write-is-abandoned
  (when-not @#'org.httpkit.server/no-ring-websockets?
    (let [{:keys [opened stop port]} (ring-websocket-server)
          sock (ws-handshake-client port 1024)]
      (try
        (let [socket (await-result opened)]
          (is (not= ::timeout socket) "Ring websocket never opened")
          (when (not= ::timeout socket)
            (let [failed (promise)
                  successes (atom 0)
                  failures (atom 0)
                  payload (ByteBuffer/wrap (byte-array (* 16 1024 1024)))]
              (ws/send socket payload
                       (fn [] (swap! successes inc))
                       (fn [error]
                         (swap! failures inc)
                         (deliver failed error)))
              (is (zero? @successes)
                  "a stalled write must remain pending before disconnect")
              (.close ^Socket sock)
              (is (instance? Throwable (await-result failed))
                  "disconnect must fail a retained asynchronous write")
              (Thread/sleep 50)
              (is (= 1 @failures))
              (is (zero? @successes)))))
        (finally
          (when-not (.isClosed ^Socket sock) (.close ^Socket sock))
          (stop))))))

(deftest the-ceiling-closes-a-connection-nobody-drains
  (testing "max-queued-bytes bounds the damage"
    ;; The ENFORCEMENT. Closing is honest rather than harsh: past this point
    ;; the queue is memory the peer has given no sign it will consume.
    (with-stalled-connection
      {:queue-high-water-bytes 0 :queue-low-water-bytes 0   ; signal off: ceiling only
       :max-queued-bytes (* 2 1024 1024)}
      (fn [ch]
        (blast! ch 2000 (fn [_] (not (hk/open? ch))))
        (is (not (hk/open? ch))
            "an undrained connection must be closed, not queued forever")))))

(deftest the-old-unbounded-behaviour-is-still-reachable
  (testing "both bounds off restores exactly the previous semantics"
    ;; Someone depending on the old behaviour must be able to keep it.
    (with-stalled-connection
      {:queue-high-water-bytes 0 :queue-low-water-bytes 0 :max-queued-bytes 0}
      (fn [ch]
        (let [{:keys [unwritable]} (blast! ch 200 (constantly false))]
          (is (zero? unwritable)
              "with the signal off, writable? must never go false")
          (is (hk/open? ch)))))))
