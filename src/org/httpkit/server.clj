(ns org.httpkit.server
  (:require
   [clojure.string :as str]
   [org.httpkit.encode :refer [base64-encode]]
   [org.httpkit.utils :as utils])

  (:import
   [org.httpkit.server AsyncChannel HttpServer RingHandler ProxyProtocolOption HttpServer$AddressFinder HttpServer$ServerChannelFactory Frame$PingFrame Frame$PongFrame]
   [org.httpkit.logger ContextLogger EventLogger EventNames]
   [java.net InetSocketAddress]
   [java.nio ByteBuffer]
   [java.nio.channels ServerSocketChannel]
   [java.util Base64]
   [java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor TimeUnit]
   java.security.MessageDigest))

(set! *warn-on-reflection* true)

;;;; Ring server

(defprotocol IHttpServer
  (server-port   [http-server] "Given an HttpServer, returns server's local port.")
  (server-status [http-server] "Given an HttpServer, returns server's status e/o #{:stopped :running :stopping}.")
  (-server-stop! [http-server opts]))

(extend-type HttpServer
  IHttpServer
  (server-port   [s] (.getPort s))
  (server-status [s] (keyword (str/lower-case (.name (.getStatus s)))))
  (-server-stop! [s {:keys [timeout] :or {timeout 100}}]
    (let [p_ (promise)]
      (when (.stop s timeout #(deliver p_ true))
        p_))))

(defn server-stop!
  "Signals given `HttpServer` to stop.

  If     already stopping: returns nil.
  If not already stopping: returns a Promise that will be delivered once
  server thread actually completes.

  Options:
    :timeout ; Max msecs to allow existing requests to complete before attempting
             ; interrupt (default 100)."

  ([http-server     ] (-server-stop! http-server nil))
  ([http-server opts] (-server-stop! http-server opts)))

(defn server-join
  "Joins given `HttpServer` server thread, blocking the current thread until
  server is stopped."
  [^HttpServer server]
  (.join server))

(defn new-worker
  "Returns {:keys [n-cores type pool ...]} where `:pool` is a new
  `java.util.concurrent.ExecutorService` for handling server requests.

  When on JVM 21+, uses `newVirtualThreadPerTaskExecutor` by default.
  Otherwise creates a standard `ThreadPoolExecutor` with default min and max
  thread count auto-selected based on currently available processor count."

  [{:keys [queue-size n-min-threads n-max-threads prefix allow-virtual?] :as opts}]
  (utils/new-worker
    {:default-prefix "http-kit-server-worker-"
     :default-queue-type :array
     :default-queue-size (* 1024 20)
     :n-min-threads-factor  1.0 ; => 8   threads on 8 core system, etc.
     :n-max-threads-factor 16.0 ; => 128 threads on 8 core system, etc.
     :keep-alive-msecs 0}

    (assoc opts ; Support old `run-server` opts
      :n-threads (get opts :n-threads (:thread             opts))
      :prefix    (get opts :prefix    (:worker-name-prefix opts)))))

(comment (new-worker {}))

(declare wrap-ring-websocket)

(defn run-server
  "Starts a mostly[1] Ring-compatible HttpServer with options:

    :ip                         ; Which IP to bind (default: 0.0.0.0)
    :port                       ; Which port to listen to for incoming requests

    :worker-pool                ; `java.util.concurrent.ExecutorService` or delay to use
                                ; for handling requests. Defaults to (:pool (new-worker {})).
                                ; See `new-worker` for details.

    :max-body                   ; Max HTTP body size in bytes (default: 8MB)
    :max-ws                     ; Max WebSocket message size in bytes (default: 4MB)
    :max-line                   ; Max HTTP header line size in bytes (default: 8KB)

    :max-queued-bytes           ; Per-connection close threshold for queued write
                                ; bytes. Concurrent producers may overshoot before
                                ; the selector closes. DEFAULT 0 (OFF).
                                ; Off because a size threshold cannot yet tell a
                                ; stalled peer from a large response: http-kit
                                ; materialises a whole body into one buffer, so a
                                ; 100MB download to a healthy client arrives as one
                                ; 100MB enqueue. Enable it for servers whose
                                ; responses are small and incremental (WebSocket,
                                ; SSE). Worst case is this times max connections.
    :queue-high-water-bytes     ; Per-connection mark above which `writable?` is
                                ; false (default: 64KB, as Netty). <=0 disables.
    :queue-low-water-bytes      ; ...and below which it becomes true again
                                ; (default: 32KB). Two marks give hysteresis: with
                                ; one, a connection at the boundary flips on every
                                ; write. Must not exceed the high mark.

    :proxy-protocol             ; Proxy protocol e/o #{:disable :enable :optional}

    :server-header              ; The \"Server\" header, disabled if nil. Default: \"http-kit\".

    :legacy-unsafe-remote-addr? ; If true (default), populates :remote-addr from
                                ; X-Forwarded-For header if present. This is INSECURE as it
                                ; allows IP spoofing. If false, :remote-addr always contains
                                ; the immediate connection's IP (socket address), matching
                                ; Ring's standard behavior. The X-Forwarded-For header remains
                                ; available in :headers for parsing trusted proxies.
                                ; In some future version, this option will be defaulted to false

    :error-logger               ; (fn [msg ex])  -> log errors
    :warn-logger                ; (fn [msg ex])  -> log warnings
    :event-logger               ; (fn [ev-name]) -> log events
    :event-names                ; Map of http-kit event names to loggable event names

    :legacy-content-length? ; When true (default), calculates HEAD Content-Length from its body.
                            ; When false, a HEAD handler may provide Content-Length without a body.

    ;; These opts may be used for Unix Domain Socket (UDS) support, see README:
    :address-finder             ; (fn []) -> `java.net.SocketAddress` (ip/port ignored)
    :channel-factory            ; (fn [java.net.SocketAddress]) -> `java.nio.channels.SocketChannel`

  If :legacy-return-value? is
    true  (default)             ; Returns a (fn stop-server [& {:keys [timeout] :or {timeout 100}}])
    false (recommended)         ; Returns the `HttpServer` which can be used with `server-port`,
                                ; `server-status`, `server-stop!`, `server-join`, etc.

  The server also supports the following JVM properties:

     `org.http-kit.memmap-file-threshold`
       Files above this size (in MB) are mapped into memory for efficiency when served.
       Memory mapping could result to file locking. Defaults to 20 (MB).

  [1] Ref. http://http-kit.org/migration.html for differences."

  [handler
   & [{:keys [ip port max-body max-ws max-line
              proxy-protocol worker-pool
              error-logger warn-logger event-logger event-names
              legacy-return-value? legacy-unsafe-remote-addr? legacy-content-length?
              server-header address-finder
              max-queued-bytes queue-high-water-bytes queue-low-water-bytes
              channel-factory ring-async?] :as opts

       :or   {ip         "0.0.0.0"
              port       8090
              max-body   8388608
              max-ws     4194304
              max-line   8192
              proxy-protocol :disable
              legacy-return-value? true
              legacy-unsafe-remote-addr? true
              server-header "http-kit"
              ring-async? false
              legacy-content-length? true}}]]

  (let [queue-high-water-bytes
        (long (if (some? queue-high-water-bytes)
                queue-high-water-bytes
                (* 64 1024)))
        ;; A non-positive high mark disables the signal, including its low
        ;; mark. That makes the documented one-option opt-out work.
        queue-low-water-bytes
        (long (if (pos? queue-high-water-bytes)
                (if (some? queue-low-water-bytes)
                  queue-low-water-bytes
                  (* 32 1024))
                0))
        max-queued-bytes (long (or max-queued-bytes 0))
        _ (when (and (pos? queue-high-water-bytes)
                     (or (neg? queue-low-water-bytes)
                         (> queue-low-water-bytes queue-high-water-bytes)))
            (throw (IllegalArgumentException.
                    (str ":queue-low-water-bytes (" queue-low-water-bytes ") must be non-negative"
                         " and not exceed :queue-high-water-bytes (" queue-high-water-bytes ")"))))

        ^ContextLogger err-logger
        (if error-logger
          (reify ContextLogger (log [this message error] (error-logger message error)))
          (do ContextLogger/ERROR_PRINTER))

        ^ContextLogger warn-logger
        (if warn-logger
          (reify ContextLogger (log [this message error] (warn-logger message error)))
          HttpServer/DEFAULT_WARN_LOGGER)

        ^EventLogger evt-logger
        (if event-logger
          (reify EventLogger (log [this event] (event-logger event)))
          (do    EventLogger/NOP))

        ^EventNames evt-names
        (cond
          (nil?                 event-names)  EventNames/DEFAULT
          (map?                 event-names) (EventNames. event-names)
          (instance? EventNames event-names)              event-names
          :else
          (throw
            (IllegalArgumentException.
              (format "Invalid event-names: (%s) %s"
                (class event-names) (pr-str event-names)))))

        worker-pool (or (force worker-pool) (:pool (new-worker (get opts :pool-opts opts))))

        ^org.httpkit.server.IHandler h
        (RingHandler.
         (wrap-ring-websocket handler) ring-async? worker-pool
         err-logger evt-logger evt-names server-header legacy-content-length?)

        ^ProxyProtocolOption proxy-enum
        (case proxy-protocol
          :enable   ProxyProtocolOption/ENABLED
          :disable  ProxyProtocolOption/DISABLED
          :optional ProxyProtocolOption/OPTIONAL)

        ^HttpServer$AddressFinder address-finder
        (if address-finder
          (reify HttpServer$AddressFinder (findAddress [this] ^java.net.SocketAddress (address-finder)))
          (reify HttpServer$AddressFinder (findAddress [this] (InetSocketAddress. ^String ip ^Long port))))

        ^HttpServer$ServerChannelFactory channel-factory
        (if channel-factory
          (reify HttpServer$ServerChannelFactory (createChannel [this addr] (channel-factory addr)))
          (reify HttpServer$ServerChannelFactory (createChannel [this addr] (ServerSocketChannel/open))))

        ^HttpServer
        s (HttpServer. address-finder channel-factory h
                       ^long max-body ^long max-line ^long max-ws proxy-enum ^String server-header
                       (boolean legacy-unsafe-remote-addr?)
                       err-logger
                       warn-logger
                       evt-logger
                       evt-names)]

    ;; Set after construction rather than threaded through every constructor
    ;; overload; these are volatile and read on each write. Values were
    ;; normalized and validated before constructing (and binding) the server.
    (set! (.-maxQueuedBytes s) max-queued-bytes)
    (set! (.-queueHighWaterBytes s) queue-high-water-bytes)
    (set! (.-queueLowWaterBytes s) queue-low-water-bytes)

    (.start s)

    (if-not legacy-return-value?
      s
      (with-meta
        (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
          (.stop s timeout)
          nil)

        {:local-port (.getPort s)
         :server               s}))))

;;;; WebSockets

(defn sec-websocket-accept [sec-websocket-key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (base64-encode
     (.digest md (.getBytes (str sec-websocket-key websocket-13-guid))))))

(def ^{:deprecated "v2.4.0 (2020-07-30)" :no-doc true} accept
  "DEPRECATED: prefer `sec-websocket-accept`" sec-websocket-accept)

(defn- header-has-token? [headers header token]
  (when-let [value (get headers header)]
    (some #(= token (str/lower-case (str/trim %)))
      (str/split value #"[,\n]"))))

(defn- valid-websocket-key? [key]
  (and
    (string? key)
    (try
      (let [decoded (.decode (Base64/getDecoder) ^String key)]
        (and
          (= 16 (alength decoded))
          (= key (.encodeToString (Base64/getEncoder) decoded))))
      (catch IllegalArgumentException _ false))))

(defn websocket-handshake-check
  "Returns `sec-ws-accept` string iff given Ring request is a valid
  WebSocket handshake."
  [ring-req]
  (let [headers    (:headers ring-req)
        sec-ws-key (get headers "sec-websocket-key")]
    (when
      (and
        (= :get (:request-method ring-req))
        (= "HTTP/1.1" (:protocol ring-req))
        (header-has-token? headers "upgrade" "websocket")
        (header-has-token? headers "connection" "upgrade")
        (= "13" (get headers "sec-websocket-version"))
        (valid-websocket-key? sec-ws-key))
      (sec-websocket-accept sec-ws-key))))

(defn send-checked-websocket-handshake!
  "Given an AsyncChannel and `sec-ws-accept` string, unconditionally
  sends handshake to upgrade given AsyncChannel to a WebSocket.
  See also `websocket-handshake-check`."
  [^AsyncChannel ch ^String sec-ws-accept]
  (.sendHandshake ch
    {"Upgrade" "websocket"
     "Connection" "Upgrade"
     "Sec-WebSocket-Accept" sec-ws-accept}))

(defn send-websocket-handshake!
  "Returns true iff successfully upgraded a valid WebSocket request."
  [^AsyncChannel ch ring-req]
  (when-let [sec-ws-accept (websocket-handshake-check ring-req)]
    (send-checked-websocket-handshake! ch sec-ws-accept)
    true))

;;;; Channel API

(defprotocol Channel
  "Unified asynchronous channel interface for HTTP (streaming or long-polling)
   and WebSocket."

  (open?      [ch] "Returns true iff channel is open.")
  (websocket? [ch] "Returns true iff channel is a WebSocket.")
  (close      [ch]
    "Closes the channel. Idempotent: returns true if the channel was actually
    closed, or false if it was already closed.")

  (writable? [ch]
    "Is the peer keeping up?

    False once more than `:queue-high-water-bytes` is queued for this
    connection, and true again only once the queue falls below
    `:queue-low-water-bytes` -- two marks, so a connection sitting at the
    boundary does not flip state on every write.

    `send!` is unaffected: a write past the mark is still accepted. This is how
    a caller decides whether to offer more, and `on-writable` is how it learns
    it may resume. Same shape as Netty's `Channel.isWritable()`.")

  (queued-bytes [ch]
    "Bytes accepted from the application but not yet written to the socket for
    this channel. A gauge, for metrics; stale on return by construction.")

  (on-writable [ch callback]
    "Register a one-shot `(fn [])` continuation for writable state.

    If the channel is already writable it is scheduled immediately; otherwise
    it is called once when writability is restored. Registration and state
    observation are atomic, so the restore edge cannot be lost after a caller
    observed `writable?` as false.

    Runs on the IO thread: offer more data and return, do not block.")

  (send! [ch data] [ch data close-after-send?]
    "Sends data to client and returns true if the data was successfully sent,
    or false if the channel is closed. Data is sent directly to the client,
    NO RING MIDDLEWARE IS APPLIED.

    When unspecified, `close-after-send?` defaults to true for HTTP channels
    and false for WebSocket.

    Data form: {:headers _ :status _ :body _} or just body. Note that :headers
    and :status will be stripped for WebSocket and for HTTP streaming responses
    after the first.

    For WebSocket, a text frame is sent to client if data is String,
    a binary frame when data is byte[] or InputStream. For for HTTP streaming
    responses, data can be one of the type defined by Ring spec")

  (on-receive [ch callback]
    "Sets handler (fn [message]) for notification of client WebSocket
    messages. Message ordering is guaranteed by server.

    The message argument could be a string or a byte[].")

  (on-ping [ch callback]
    "Sets handler (fn [data]) for notification of client WebSocket pings. The
    data param represents application data and will by a byte[].")

  (on-close [ch callback]
    "Sets handler (fn [status-code]) for notification of channel being closed by
    the server or client. Handler will be invoked at most once. Useful for clean-up.

    Callback status argument:
      :server-close   : Channel closed by sever
      :client-close   : HTTP channel closed by client
      :normal         : WebSocket closed by client (CLOSE_NORMAL)
      :going-away     : WebSocket closed by client (CLOSE_GOING_AWAY)
      :protocol-error : WebSocket closed by client (CLOSE_PROTOCOL_ERROR)
      :unsupported    : WebSocket closed by client (CLOSE_UNSUPPORTED)
      :unknown        : WebSocket closed by client (unknown reason)"))

(extend-type AsyncChannel
  Channel
  (open?      [ch] (not (.isClosed ch)))
  (websocket? [ch] (.isWebSocket   ch))
  (close      [ch] (.serverClose   ch 1000))
  (writable?    [ch] (.writable     ch))
  (queued-bytes [ch] (.queuedBytes  ch))
  (on-writable  [ch callback] (.setWritableHandler ch callback))

  (send!
    ([ch data                  ] (.send ch data (not (websocket? ch))))
    ([ch data close-after-send?] (.send ch data (boolean close-after-send?))))

  (on-receive [ch callback] (.setReceiveHandler ch callback))
  (on-close   [ch callback] (.setCloseHandler   ch callback))
  (on-ping    [ch callback] (.setPingHandler    ch
                              (fn callback* [data]
                                (.send ch (Frame$PongFrame. data) false)
                                (callback data)))))

(defmacro with-channel
  "DEPRECATED: this macro has potential race conditions, Ref. #318.
  Prefer `as-channel` instead."
  {:deprecated "v2.4.0 (2020-07-30)"}
  [ring-req ch-name & body]
  `(let [ring-req# ~ring-req
         ~ch-name (:async-channel ring-req#)]

     (if (:websocket? ring-req#)
       (if-let [sec-ws-accept# (websocket-handshake-check ring-req#)]
         (do
           (send-checked-websocket-handshake! ~ch-name sec-ws-accept#)
           ~@body
           {:body ~ch-name})
         {:status 400 :body "Bad Sec-WebSocket-Key header"})
       (do ~@body {:body ~ch-name}))))

(def ^:private bad-ring-websocket-resp
  {:status 400
   :headers {"Content-Type" "text/plain"}
   :body "Bad Sec-Websocket-Key header"})

(defn as-channel
  "Returns `{:body ch}`, where `ch` is the request's underlying
  asynchronous HTTP or WebSocket `AsyncChannel`.

  Main options:
    :init       - (fn [ch])             for misc pre-handshake setup.
    :on-receive - (fn [ch message])     called for client WebSocket messages.
    :on-ping    - (fn [ch data])        called for client WebSocket pings.
    :on-close   - (fn [ch status-code]) called when AsyncChannel is closed.
    :on-open    - (fn [ch])             called when AsyncChannel is ready for `send!`, etc.

  See `Channel` protocol for more info on handlers and `AsyncChannel`s.
  See `org.httpkit.timer` ns for optional timeout utils.

  ---

  Example - Async HTTP response:

    (def clients_ (atom #{}))
    (defn my-async-handler [ring-req]
      (as-channel ring-req
        {:on-open (fn [ch] (swap! clients_ conj ch))}))

    ;; Somewhere else in your code
    (doseq [ch @clients_]
      (swap! clients_ disj ch)
      (send! ch {:status 200 :headers {\"Content-Type\" \"text/html\"}
                 :body \"Your async response\"}
        ;; false ; Uncomment to use chunk encoding for HTTP streaming
        ))

  Example - WebSocket response:

    (defn my-chatroom-handler [ring-req]
      (if-not (:websocket? ring-req)
        {:status 200 :body \"Welcome to the chatroom! JS client connecting...\"}
        (as-channel ring-req
          {:on-receive (fn [ch message]     (println \"on-receive:\" message))
           :on-close   (fn [ch status-code] (println \"on-close:\"   status-code))
           :on-open    (fn [ch]             (println \"on-open:\"    ch))})))"

  [ring-req {:keys [on-receive on-ping on-close on-open init on-handshake-error]
             :or   {on-handshake-error (fn [ch] (send! ch bad-ring-websocket-resp true))}}]

  (when-let [ch (:async-channel ring-req)]

    (when-let [f init]     (f ch))
    (when-let [f on-close] (org.httpkit.server/on-close ch (partial f ch)))

    (if (:websocket? ring-req)
      (if-let [sec-ws-accept (websocket-handshake-check ring-req)]
        (do
          (when-let [f on-receive] (org.httpkit.server/on-receive ch (partial f ch)))
          (when-let [f on-ping]    (org.httpkit.server/on-ping    ch (partial f ch)))
          (send-checked-websocket-handshake! ch sec-ws-accept)
          (when-let [f on-open] (f ch)))
        (when-let [f on-handshake-error] (f ch)))
      (when-let [f on-open] (f ch)))

    {:body ch}))

(defn- buffer->bytes [^ByteBuffer buf]
  (let [len (.remaining buf)
        bs  (byte-array len)]
    (.get buf bs 0 len)
    bs))

(defn- ring-websocket-data [msg]
  (cond
    (instance? CharSequence msg) (.toString ^CharSequence msg)
    (instance? ByteBuffer msg)   (buffer->bytes msg)
    :else
    (throw (IllegalArgumentException.
            (str "Ring WebSocket send expects CharSequence or ByteBuffer, got "
                 (some-> msg class .getName))))))

(def ^:private no-ring-websockets?
  "Should support for `org.ring-clojure/ring-websocket-protocols` be disabled?
  There shouldn't be any reason besides testing to use this."
  (when (= (System/getProperty "http-kit.no-ring-websockets") "true")
    (println "Note: disabled Ring WebSocket protocol support for testing.")
    true))

(defn- ring-websocket-resp [rreq rresp]
  (utils/compile-if
    (when-not no-ring-websockets? (require '[ring.websocket.protocols :as wsp]) true)
    (let [^AsyncChannel ch (:async-channel rreq)
          listener (:ring.websocket/listener rresp)]
      (if (and (:websocket? rreq) (some? listener))
        (if-let [sec-ws-accept (websocket-handshake-check rreq)]
          (let [l listener
                sock
                (reify
                  wsp/Socket
                  (-open? [_]        (not (.isClosed    ch)))
                  (-send  [_ msg]         (.send        ch (ring-websocket-data msg)                      false))
                  (-ping  [_ bb-data]     (.send        ch (Frame$PingFrame.     (buffer->bytes bb-data)) false))
                  (-pong  [_ bb-data]     (.send        ch (Frame$PongFrame.     (buffer->bytes bb-data)) false))
                  (-close [_ code reason] (.serverClose ch code reason))

                  wsp/AsyncSocket
                  (-send-async [_ msg succeed fail]
                    (let [result (try
                                   {:immediate? (.sendAsync ch (ring-websocket-data msg)
                                                            succeed fail)}
                                   (catch Throwable t {:error t}))]
                      (if-let [error (:error result)]
                        (fail error)
                        (when (:immediate? result) (succeed))))))]

            (when (satisfies? wsp/PingListener listener)
              (.setPingHandler    ch (fn [ba-msg]      (wsp/on-ping    l sock                       (ByteBuffer/wrap ba-msg)))))
            (.setPongHandler      ch (fn [ba-msg]      (wsp/on-pong    l sock                       (ByteBuffer/wrap ba-msg))))
            (.setReceiveHandler   ch (fn [msg]         (wsp/on-message l sock (if (string? msg) msg (ByteBuffer/wrap msg)))))
            (.setCloseRingHandler ch (fn [code reason] (wsp/on-close   l sock code reason)))

            (let [headers
                  {"Upgrade"              "websocket"
                   "Connection"           "Upgrade"
                   "Sec-WebSocket-Accept" sec-ws-accept}]

              (.sendHandshake ch
                (if-let [protocol (:ring.websocket/protocol rresp)]
                  (assoc headers "Sec-WebSocket-Protocol" protocol)
                  headers)))

            (wsp/on-open listener sock)
            {:body ch})

          bad-ring-websocket-resp)
        rresp))
    rresp))

(defn- wrap-ring-websocket [ring-handler]
  (fn ring-handler*
    ([rreq              ] (ring-websocket-resp rreq (ring-handler rreq)))
    ([rreq respond raise]
     (ring-handler rreq
       (fn respond* [rresp] (respond (ring-websocket-resp rreq rresp)))
       raise))))
