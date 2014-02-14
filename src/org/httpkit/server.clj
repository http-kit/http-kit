(ns org.httpkit.server
  (:import [org.httpkit.server AsyncChannel HttpServer RingHandler]
           javax.xml.bind.DatatypeConverter
           java.security.MessageDigest))

;;;; Ring server

(defn run-server
  "Starts (mostly*) Ring-compatible HTTP server and returns a function that stops
  the server, which can take an optional timeout(ms)
  param to wait existing requests to be finished, like (f :timeout 100).

  * See http://http-kit.org/migration.html for differences."
  [handler {:keys [port thread ip max-body max-line worker-name-prefix queue-size max-ws]
            :or   {ip "0.0.0.0"  ; which ip (if has many ips) to bind
                   port 8090     ; which port listen incomming request
                   thread 4      ; http worker thread count
                   queue-size 20480 ; max job queued before reject to project self
                   worker-name-prefix "worker-" ; woker thread name prefix
                   max-body 8388608             ; max http body: 8m
                   max-ws  4194304              ; max websocket message size: 4m
                   max-line 4096}}]  ; max http inital line length: 4K
  (let [h (RingHandler. thread handler worker-name-prefix queue-size)
        s (HttpServer. ip port h max-body max-line max-ws)]
    (.start s)
    (with-meta (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
                 ;; graceful shutdown:
                 ;; 1. server stop accept new request
                 ;; 2. wait for existing requests to finish
                 ;; 3. close the server
                 (.stop s timeout))
      {:local-port (.getPort s)})))

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
  (on-close [ch callback] (.setCloseHandler ch callback)))

;;;; WebSocket
(defn accept [key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (DatatypeConverter/printBase64Binary
     (.digest md (.getBytes (str key websocket-13-guid))))))

(defmacro with-channel
  "Evaluates body with `ch-name` bound to the request's underlying asynchronous
  HTTP or WebSocket channel, and returns {:body AsyncChannel} as an
  implementation detail.

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
        (on-close   ch (fn [status] (println \"on-close:\" status)))
        (send! ch \"First chat message!\"))))

  Channel API (see relevant docstrings for more info):
    (open? [ch])
    (close [ch])
    (websocket? [ch])
    (send! [ch data] [ch data close-after-send?])
    (on-receieve [ch callback])
    (on-close [ch callback])

  See org.httpkit.timer ns for optional timeout facilities."
  [request ch-name & body]
  `(let [~ch-name (:async-channel ~request)]
     (if (:websocket? ~request)
       (if-let [key# (get-in ~request [:headers "sec-websocket-key"])]
         (do (.sendHandshake ~(with-meta ch-name {:tag `AsyncChannel})
                             {"Upgrade"    "websocket"
                              "Connection" "Upgrade"
                              "Sec-WebSocket-Accept" (accept key#)})
             ~@body
             {:body ~ch-name})
         {:status 400 :body "Bad Sec-WebSocket-Key header"})
       (do ~@body
           {:body ~ch-name}))))
