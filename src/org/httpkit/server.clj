(ns org.httpkit.server
  (:import [org.httpkit.server AsyncChannel HttpServer RingHandler]
           javax.xml.bind.DatatypeConverter
           java.security.MessageDigest))

;;;; Ring server

(defn run-server
  "Starts (mostly*) Ring-compatible HTTP server and returns a nullary function
  that stops the server.

  * See http://http-kit.org/migration.html for differences."
  [handler {:keys [port thread ip max-body max-line worker-name-prefix queue-size]
            :or   {ip "0.0.0.0"  ; which ip (if has many ips) to bind
                   port 8090     ; which port listen incomming request
                   thread 4      ; http worker thread count
                   queue-size 20480 ; max job queued before reject to project self
                   worker-name-prefix "worker-" ; woker thread name prefix
                   max-body 8388608             ; max http body: 8m
                   max-line 4096}}]  ; max http inital line length: 4K
  (let [h (RingHandler. thread handler worker-name-prefix queue-size)
        s (HttpServer. ip port h max-body max-line)]
    (.start s)
    (fn stop-server [] (.close h) (.stop s))))

;;;; Asynchronous extension

(defprotocol Channel
  "Unified asynchronous channel interface for HTTP (streaming or long-polling)
  and WebSockets."

  (open? [ch] "Returns true iff channel is open.")
  (close [ch]
    "Closes the channel. Idempotent: returns true if the channel was actually
    closed, or false if it was already closed.")
  (websocket? [ch] "Returns true iff channel is a WebSocket.")
  (send! [ch data] [ch data close-after-send?]
    "Sends data to client and returns true if the data was successfully sent,
    or false if the channel is closed.

    When unspecified, `close-after-send?` defaults to true for HTTP channels and
    false for WebSockets.

    Data form: {:headers _ :status _ :body _} or just body. Note that :headers
    and :status will be stripped for WebSockets and for HTTP streaming responses
    after the first.")
  (on-receive [ch callback]
    "Sets handler (fn [message-string]) for notification of client WebSocket
    messages. Message ordering is guaranteed by server.")
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
      :unknown        : WebSocket closed by client (unknown reason)")

  ;;; just experiment, will be removed if not needed
  (alter-send-hook [ch f]
    "Callback: (fn [old-hook] new-hook)
     hook : (fn [data websocket? first-send?] data-write-to-client)
     Do something with the sending data (like JSON encoding), the return value is sending off")
  (alter-receive-hook [ch f]
    "Callback: (fn [old-hook] new-hook)
     hook: (fn [string] ret), ret is pass to on-receive handler
     Do something with the receiving data (like JSON decoding), the return value is pass to receive handler"))

(extend-type AsyncChannel
  Channel
  (open? [ch] (not (.isClosed ch)))
  (close [ch] (.serverClose ch 1000))
  (websocket? [ch] (.isWebSocket ch))
  (send!
    ([ch data] (.send ch data false))
    ([ch data close-after-send?] (.send ch data (true? close-after-send?))))
  (alter-send-hook [ch f] (.alterSentHook ch f))
  (alter-receive-hook [ch f] (.alterReceiveHook ch f))
  (on-receive [ch callback] (.setReceiveHandler ch callback))
  (on-close [ch callback] (.setCloseHandler ch callback)))

;;;; WebSockets
(defn accept [key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (DatatypeConverter/printBase64Binary
     (.digest md (.getBytes (str key websocket-13-guid))))))

(defn websocket? [request] (:websocket? request))

;;; experiment, will remove if find not need
(defn set-global-hook [send-hook receive-hook]
  (AsyncChannel/setGlobalHook send-hook receive-hook))

(defmacro ws-response
  "If request is a WebSocket handshake request, evaluates `then` form with
  `conn-name` binding. and returns a WebSocket Upgrade response. Otherwise
  evaluates `else` form and returns a standard Ring response:

   (defn my-chatroom-handler [request]
     (ws-response request ws-conn
       (println \"New WebSocket connection:\" ws-conn)
           (on-receive ws-conn (fn [message] (println \"on-mesg:\"  message)))
           (on-close  ws-conn (fn [status]  (println \"on-close:\" status)))
           (send! ws-conn \"Welcome to the chatroom!\"))))"
  [request conn-name & then]
  `(let [key# (get-in ~request [:headers "sec-websocket-key"])
         ~conn-name ^AsyncChannel (:async-channel ~request)]
     (if (and key# (:websocket? ~request))
       (do
         (.sendHandshake ~conn-name {"Upgrade"    "websocket"
                                     "Connection" "Upgrade"
                                     "Sec-WebSocket-Accept" (accept key#)
                                     ;; "Sec-WebSocket-Protocol" "13"
                                     })
         ~@then
         {:body (:async-channel ~request)})
       {:status 400 :body "Not websocket or Sec-WebSocket-Key header missing"})))

(defmacro async-response
  "Wraps body so that a standard Ring response will be returned to caller when
  `(callback-name ring-response|body)` is executed in any thread:

    (defn my-async-handler [request]
      (async-response request respond!
        (future (respond! {:status  200
                          :headers {\"Content-Type\" \"text/html\"}
                          :body    \"This is an async response!\"}))))

  The caller's request will block while waiting for a response (see
  Ajax long polling example as one common use case).

  NB: The response is sent directly to the client, no Ring middleware is
  applied.

  See org.httpkit.timer ns for optional timeout facilities."
  [request callback-name & body]
  `(let [channel# ^AsyncChannel (:async-channel ~request)
         ~callback-name (fn [data#] (.send channel# data# true))]
     ~@body
     {:body channel#}))

(defmacro streaming-response
  "Streaming response.

    (defn my-streaming-handler [request]
      (streaming-respose request channel
        (on-close channel (fn [status] (println \"closed\")))
        (send! channel {:status 200 :headers {}})
        (on-some-event   ; wait for event or data
         (send! channel data)
         (close channel))))

  Response middleware can by set by (alter-send-hook channel (fn [old] new))

  See org.httpkit.timer ns for optional timeout facilities."
  [request channel & body]
  `(let [~channel (:async-channel ~request)]
     (do ~@body)
     {:body ~channel}))

(comment
  TODO
  built library on top of the API,
  with client side JS + server side clojure,

  1. provide a unified interface.
  2. `group` concept [join a group, leave a group, sent messages to the group]
  3. can attach data to a group

  MemoryStore, RedisStore
  )