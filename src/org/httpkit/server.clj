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

;;;; Asynchronous extension

(defprotocol Channel
  "Unified asynchronous channel interface for HTTP (streaming or long-polling)
   and WebSocket."

  (open? [ch] "Returns true iff channel is open.")
  (close [ch]
    "Closes the channel. Idempotent: returns true if the channel was actually
    closed, or false if it was already closed.")
  (websocket? [ch] "Returns true iff channel is a WebSocket.")
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
  (open? [ch] (not (.isClosed ch)))
  (close [ch] (.serverClose ch 1000))
  (websocket? [ch] (.isWebSocket ch))
  (send!
    ([ch data] (.send ch data (not (websocket? ch))))
    ([ch data close-after-send?] (.send ch data (boolean close-after-send?))))
  (on-receive [ch callback] (.setReceiveHandler ch callback))
  (on-ping [ch callback] (.setPingHandler ch callback))
  (on-close [ch callback] (.setCloseHandler ch callback)))

;;;; WebSocket

(defn sec-websocket-accept [sec-websocket-key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (base64-encode
     (.digest md (.getBytes (str sec-websocket-key websocket-13-guid))))))

(def accept "DEPRECATED for `sec-websocket-accept" sec-websocket-accept)

(defn websocket-handshake-check
  "Returns `sec-ws-accept` string iff given Ring request is a valid
  WebSocket handshake."
  [^AsyncChannel ch ring-req]
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
  (when-let [sec-ws-accept (websocket-handshake-check ch ring-req)]
    (send-checked-websocket-handshake! ch sec-ws-accept)))

;; (defn websocket-req? [ring-req] (:websocket?    ring-req))
;; (defn async-channel  [ring-req] (:async-channel ring-req))
;; (defn async-response [ring-req] {:body (:async-channel ring-req)})

(defmacro with-channel
  "Evaluates body with `ch-name` bound to the request's underlying
  asynchronous HTTP or WebSocket channel, and returns {:body AsyncChannel}
  as an implementation detail.

  Note: for WebSocket requests, WebSocket handshake will be sent *after*
  body is evaluated. I.e. body cannot call `send!`.

  ;; Asynchronous HTTP response (with optional streaming)
  (defn my-async-handler [request]
    (with-channel request ch ; Request's channel
      ;; Make ch available to whoever can deliver the response to it; ex.:
      (swap! clients conj ch)))   ; given (def clients (atom #{}))
  ;; Some place later:
  (doseq [ch @clients]
    (swap! clients disj ch)
    (send! ch {:status  200
                 :headers {\"Content-Type\" \"text/html\"}
                 :body your-async-response}
             ;; false ; Uncomment to use chunk encoding for HTTP streaming
             )))

  ;; WebSocket response
  (defn my-chatroom-handler [request]
    (if-not (:websocket? request)
      {:status 200 :body \"Welcome to the chatroom! JS client connecting...\"}
      (with-channel request ch
        (println \"New WebSocket channel:\" ch)
        (on-receive ch (fn [msg]    (println \"on-receive:\" msg)))
        (on-close   ch (fn [status] (println \"on-close:\" status))))))

  Channel API (see relevant docstrings for more info):
    (open? [ch])
    (close [ch])
    (websocket? [ch])
    (send! [ch data] [ch data close-after-send?])
    (on-receieve [ch callback])
    (on-close [ch callback])

  See org.httpkit.timer ns for optional timeout facilities."
  [ring-req ch-name & body]
  `(let [ring-req# ~ring-req
         ~ch-name (:async-channel ring-req#)]

     (if (:websocket? ring-req#)
       (if-let [sec-ws-accept# (websocket-handshake-check ~ch-name ring-req#)]
         (do
           ~@body ; Eval body before handshake to allow hooks to be established, Ref. #318
           (send-checked-websocket-handshake! ~ch-name sec-ws-accept#)
           {:body ~ch-name})
         {:status 400 :body "Bad Sec-WebSocket-Key header"})
       (do ~@body {:body ~ch-name}))))
