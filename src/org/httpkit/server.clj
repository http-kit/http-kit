(ns org.httpkit.server
  (:require [clojure.tools.macro :as macro])
  (:import  [org.httpkit.server AsyncChannel HttpServer RingHandler]
            javax.xml.bind.DatatypeConverter
            java.security.MessageDigest))

;;;; Ring server

(defn run-server
  "Starts Ring-compatible HTTP server and returns a nullary function that stops
  the server."
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
  "Asynchronous channel for HTTP streaming/long polling & websocekt"
  (open? [ch] "Tells whether or not this channel is still open")
  (close [ch]
    "Closes this channel.
     If this channel is already closed then invoking this method has no effect.")
  (send! [ch data]
    "Send data to client.
     If channel is closed, then invoking this method has no effect, and return false
     For Webocket, a text frame is sent to client.
     For streaming:
     1. First call of send!, data expect to be {:headers _ :status _ :body _} or just body.
     2. Any further call, only body(String, File, InputStream, ISeq) is expected.
        The data is encoded as chunk, sent to client")
  (on-send [ch callback]
    "Callback: (fn [data])
     Do something with the sending data (like JSON encoding),
     the return value is sending off")
  (on-receive [ch callback]
    "Callback: (fn [message-string])
     Set the handler to get notified when there is message from client.
     Only valid for websocket. For streaming,
     another HTTP connection can be used to emulate the behaviour")
  (on-close [ch callback]
    "Callback: (fn [status])
     Set the handler to get notified when channel get closed, by client, or server call `close`.
     Callback is called once if server and client both close the channel.
     Useful for doing clean up.
     Status code: 0 if closed by sever;
     Closed by client: -1 for streaming, websocket: http://tools.ietf.org/html/rfc6455#section-7.4.1"))

(extend-type AsyncChannel
  Channel
  (open? [ch] (not (.isClosed ch)))
  (close [ch] (.serverClose ch 1000))
  (send! [ch data] (.send ch data false))
  (on-send [ch callback] (.setOnSendFn ch callback))
  (on-receive [ch callback] (.setReceiveHandler ch callback))
  (on-close [ch callback] (.setCloseHandler ch callback)))

;;;; WebSockets
(defn accept [key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (DatatypeConverter/printBase64Binary
     (.digest md (.getBytes (str key websocket-13-guid))))))

(defn websocket? [request] (:websocket? request))

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

  Response middleware can by set by (on-send channel callback)

  See org.httpkit.timer ns for optional timeout facilities."
  [request channel & body]
  `(let [~channel (:async-channel ~request)]
     (do ~@body)
     {:body ~channel}))