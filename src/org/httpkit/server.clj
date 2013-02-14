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

(defmacro async-response
  "Wraps body so that a standard Ring response will be returned to caller when
  `(callback-name ring-response|body)` is executed in any thread:

    (defn my-async-handler [request]
      (async-response request respond
        (future (respond {:status  200
                          :headers {\"Content-Type\" \"text/html\"}
                          :body    \"This is an async response!\"}))))

  The caller's request will block while waiting for a response (see
  Ajax long polling example as one common use case).

  NB: The response is sent directly to the client, no Ring middleware is
  applied.

  See org.httpkit.timer ns for optional timeout facilities."
  [request callback-name & body]
  `(let [channel# ^AsyncChannel (:async-channel ~request)
         ~callback-name (fn [data#]
                          (.writeChunk channel# data#
                                       (not (= false (:final data#)))))]
     (do ~@body)
     {:status 200 :body channel#}))

(comment (.get (:body (async-response respond! (future (respond! "boo"))))))

;;;; WebSockets

(defn accept [key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (DatatypeConverter/printBase64Binary
     (.digest md (.getBytes (str key websocket-13-guid))))))

(defn on-mesg
  "Register a fn to be called when there is message from client:

  (on-mesg ws-conn
     (fn [message] (println \"on-mesg\" message)))"
  [^AsyncChannel conn fn]  (.addReceiveListener conn fn))

(defn on-close
  "Register a fn to be called when the connecton is closed:

  (on-close ws-conn
    (fn [status] (println \"websocket connection closed\")))"
  [^AsyncChannel conn fn]  (.addOnCloseListener conn fn))

(defn send-mesg
  "Send message to client

  (send-mesg ws-connection \"Message from server\")"
  [^AsyncChannel conn msg] (.send conn msg))

(defn close-conn
  "Close the websocket connection."
  ([^AsyncChannel conn]        (.serverClose conn))
  ([^AsyncChannel conn status] (.serverClose conn status)))

(defmacro if-ws-request
  "If request is a WebSocket handshake request, evaluates `then` form with
  `conn-name` binding and returns a WebSocket Upgrade response. Otherwise
  evaluates `else` form and returns a standard Ring response:

   (defn my-chatroom-handler [request]
     (if-ws-request request ws-conn
       (do (println \"New WebSocket connection:\" ws-conn)
           (on-mesg   ws-conn (fn [message] (println \"on-mesg:\"  message)))
           (on-close  ws-conn (fn [status]  (println \"on-close:\" status)))
           (send-mesg ws-conn \"Welcome to the chatroom!\"))
       (deal-with-not-websocket-handshake-request)))"
  [request conn-name then else]
  `(if (:websocket ~request)
     (if-let [key# (get-in ~request [:headers "sec-websocket-key"])]
       (let [~conn-name ^AsyncChannel (:async-channel ~request)]
         (.sendHandshake ~conn-name {"Upgrade"    "websocket"
                                     "Connection" "Upgrade"
                                     "Sec-WebSocket-Accept" (accept key#)
                                     ;; "Sec-WebSocket-Protocol" "13"
                                     })
         ~then
         {:status 200 :body (:async-channel ~request)})
       {:status 400 :headers {} :body "Sec-WebSocket-Key header expected"})
     ~else))

(defmacro when-ws-request [request conn-name & body]
  `(if-ws-request ~request ~conn-name (do ~@body) nil))
