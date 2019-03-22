(ns org.httpkit.server
  (:require [org.httpkit.encode :refer [base64-encode]])
  (:import [org.httpkit.server AsyncChannel HttpServer RingHandler ProxyProtocolOption]
           [org.httpkit.logger ContextLogger EventLogger EventNames]
           java.security.MessageDigest))

;;;; Ring server

(defn run-server
  "Starts HTTP server and returns
    (fn [& {:keys [timeout] ; Timeout (msecs) to wait on existing reqs to complete
           :or   {timeout 100}}])

  Server is mostly Ring compatible, see http://http-kit.org/migration.html
  for differences.

  Options:
    :ip                 ; Which ip (if has many ips) to bind
    :port               ; Which port listen incomming request
    :thread             ; Http worker thread count
    :queue-size         ; Max job queued before reject to project self
    :max-body           ; Max http body: 8m
    :max-ws             ; Max websocket message size
    :max-line           ; Max http inital line length
    :proxy-protocol     ; Proxy protocol e/o #{:disable :enable :optional}
    :worker-name-prefix ; Worker thread name prefix
    :worker-pool        ; ExecutorService to use for request-handling (:thread,
                          :worker-name-prefix, :queue-size are ignored if set)
    :error-logger       ; Arity-2 fn (args: string text, exception) to log errors
    :warn-logger        ; Arity-2 fn (args: string text, exception) to log warnings
    :event-logger       ; Arity-1 fn (arg: string event name)
    :event-names        ; map of HTTP-Kit event names to respective loggable event names"

  [handler
   & [{:keys [ip port thread queue-size max-body max-ws max-line
              proxy-protocol worker-name-prefix worker-pool
              error-logger warn-logger event-logger event-names]

       :or   {ip         "0.0.0.0"
              port       8090
              thread     4
              queue-size 20480
              max-body   8388608
              max-ws     4194304
              max-line   8192
              proxy-protocol :disable
              worker-name-prefix "worker-"}}]]

  (let [err-logger (if error-logger
                     (reify ContextLogger
                       (log [this message error] (error-logger message error)))
                     ContextLogger/ERROR_PRINTER)
        evt-logger (if event-logger
                     (reify EventLogger
                       (log [this event] (event-logger event)))
                     EventLogger/NOP)
        evt-names  (cond
                     (nil? event-names) EventNames/DEFAULT
                     (map? event-names) (EventNames. event-names)
                     (instance? EventNames
                       event-names)     event-names
                     :otherwise         (throw (IllegalArgumentException.
                                                 (format "Invalid event-names: (%s) %s"
                                                   (class event-names) (pr-str event-names)))))
        h (if worker-pool
            (RingHandler. handler worker-pool err-logger evt-logger evt-names)
            (RingHandler. thread handler worker-name-prefix queue-size err-logger evt-logger evt-names))
        proxy-enum (case proxy-protocol
                     :enable   ProxyProtocolOption/ENABLED
                     :disable  ProxyProtocolOption/DISABLED
                     :optional ProxyProtocolOption/OPTIONAL)

        s (HttpServer. ip port h max-body max-line max-ws proxy-enum
            err-logger
            (if warn-logger
              (reify ContextLogger
                (log [this message error] (warn-logger message error)))
              HttpServer/DEFAULT_WARN_LOGGER)
            evt-logger
            evt-names)]

    (.start s)
    (with-meta
      (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
        ;; graceful shutdown:
        ;; 1. server stop accept new request
        ;; 2. wait for existing requests to finish
        ;; 3. close the server
        (.stop s timeout))

      {:local-port (.getPort s)
       :server s})))

;;;; WebSockets

(defn sec-websocket-accept [sec-websocket-key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (base64-encode
     (.digest md (.getBytes (str sec-websocket-key websocket-13-guid))))))

(def accept "DEPRECATED: prefer `sec-websocket-accept`" sec-websocket-accept)

(defn websocket-handshake-check
  "Returns `sec-ws-accept` string iff given Ring request is a valid
  WebSocket handshake."
  [ring-req]
  (when-let [sec-ws-key (get-in ring-req [:headers "sec-websocket-key"])]
    (try
      (sec-websocket-accept sec-ws-key)
      (catch Exception _ nil))))

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
    (send-checked-websocket-handshake! ch sec-ws-accept)))

;;;; Channel API

(defprotocol Channel
  "Unified asynchronous channel interface for HTTP (streaming or long-polling)
   and WebSocket."

  (open?      [ch] "Returns true iff channel is open.")
  (websocket? [ch] "Returns true iff channel is a WebSocket.")
  (close      [ch]
    "Closes the channel. Idempotent: returns true if the channel was actually
    closed, or false if it was already closed.")

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
    "Sets handler (fn [status]) for notification of channel being closed by the
    server or client. Handler will be invoked at most once. Useful for clean-up.

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
  (send!
    ([ch data                  ] (.send ch data (not (websocket? ch))))
    ([ch data close-after-send?] (.send ch data (boolean close-after-send?))))

  (on-receive [ch callback] (.setReceiveHandler ch callback))
  (on-ping    [ch callback] (.setPingHandler    ch callback))
  (on-close   [ch callback] (.setCloseHandler   ch callback)))

(defmacro with-channel
  "DEPRECATED: this macro has potential race conditions, Ref. #318.
  Prefer `as-channel` instead."
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

(defn as-channel
  "Returns `{:body ch}`, where `ch` is the request's underlying
  asynchronous HTTP or WebSocket `AsyncChannel`.

  Main options:
    :on-receive - (fn [ch message]) called for client WebSocket messages.
    :on-ping    - (fn [ch data])    called for client WebSocket pings.
    :on-close   - (fn [ch status])  called when AsyncChannel is closed.
    :on-open    - (fn [ch])         called when AsyncChannel is ready for `send!`, etc.

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
          {:on-receive (fn [ch message] (println \"on-receive:\" message))
           :on-close   (fn [ch status]  (println \"on-close:\"   status))
           :on-open    (fn [ch]         (println \"on-open:\"    ch))})))"

  [ring-req {:keys [on-receive on-ping on-close on-open on-handshake-error]
             :or   {on-handshake-error
                    (fn [ch]
                      (send! ch
                        {:status 400
                         :headers {"Content-Type" "text/plain"}
                         :body "Bad Sec-Websocket-Key header"}
                        true))}}]

  (when-let [ch (:async-channel ring-req)]

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
