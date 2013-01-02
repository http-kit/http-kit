(ns me.shenfeng.http.server
  (:require [clojure.tools.macro :as macro])
  (:import  [me.shenfeng.http.server HttpServer IListenableFuture RingHandler]
            me.shenfeng.http.ws.WsCon
            javax.xml.bind.DatatypeConverter
            java.security.MessageDigest))

;;;; Ring server

(defn run-server
  "Starts Ring-compatible HTTP server and returns a nullary function that stops
  the server."
  [handler {:keys [port thread ip max-body max-line worker-name-prefix]
            :or   {ip "0.0.0.0"
                   port 8090
                   thread 4
                   worker-name-prefix "worker-"
                   max-body 8388608 ; max http body: 8m
                   max-line 4096}}] ; max http inital line length: 4K
  (let [h (RingHandler. thread handler worker-name-prefix)
        s (HttpServer. ip port h max-body max-line)]
    (.start s)
    (fn stop-server [] (.close h) (.stop s))))

;;;; Asynchronous extension

(defmacro async-response
  "Wraps body so that a standard Ring response will be returned to caller when
  `(callback-name ring-response)` is executed in any thread:

     (defn my-async-handler! [request]
       (async-response respond!
         (future (respond! {:status  200
                            :headers {\"Content-Type\" \"text/html\"}
                            :body    \"This is an async response!\"}))))

  The caller's request will block while waiting for a response (see
  Ajax long polling example as one common use case)."
  [callback-name & body]
  `(let [data# (atom {})
         ~callback-name (fn [response#]
                          (swap! data# assoc :response response#)
                          (when-let [listener# (:listener @data#)]
                            (.run ^Runnable listener#)))]
     (do ~@body)
     {:status  200
      :headers {}
      :body    (reify IListenableFuture
                 (addListener [this# listener#]
                   (if (:response @data#)
                     (.run ^Runnable listener#)
                     (swap! data# assoc :listener listener#)))
                 (get [this#] (:response @data#)))}))

(comment (.get (:body (async-response respond! (future (respond! "boo"))))))

(defmacro defasync
  "(defn name [request] (async-response callback-name body))"
  {:arglists '(name [request] callback-name & body)}
  [name & sigs]
  (let [[name [[request] callback-name & body]]
        (macro/name-with-attributes name sigs)]
    `(defn ~name [~request] (async-response ~callback-name ~@body))))

;;;; WebSockets

(defn accept [key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (DatatypeConverter/printBase64Binary
     (.digest md (.getBytes (str key websocket-13-guid))))))

(defn on-mesg   [^WsCon conn fn]  (.addRecieveListener conn fn))
(defn on-close  [^WsCon conn fn]  (.addOnCloseListener conn fn))
(defn send-mesg [^WsCon conn msg] (.send conn msg))
(defn close-conn
  ([^WsCon conn]        (.serverClose conn))
  ([^WsCon conn status] (.serverClose conn status)))

(defmacro if-ws-request
  "If request is a WebSocket handshake request, evaluates `then` form with
  `conn-name` binding and returns a WebSocket Upgrade response. Otherwise
  evaluates `else` form and returns a standard Ring response.

      (defn my-chatroom-handler [request]
        (if-ws-request request ws-conn
          (do (println \"New WebSocket connection:\" ws-conn)
              (on-mesg   ws-conn (fn [message] (println \"on-mesg:\"  message)))
              (on-close  ws-conn (fn [status]  (println \"on-close:\" status)))
              (send-mesg ws-conn \"Welcome to the chatroom!\"))
          (render-chatroom-view)))"
  [request conn-name then else]
  `(if-let [~conn-name (:websocket ~request)]
     (if-let [key# (get-in ~request [:headers "sec-websocket-key"])]
       (do ~then
           {:status  101
            :headers {"Upgrade"    "websocket"
                      "Connection" "Upgrade"
                      "Sec-WebSocket-Accept" (accept key#)
                      ;; "Sec-WebSocket-Protocol" "13"
                      }})
       {:status 400 :headers {} :body "Sec-WebSocket-Key header expected"})
     ~else))

(defmacro when-ws-request [request conn-name & body]
  `(if-ws-request ~request ~conn-name (do ~@body) nil))

(defmacro defwshandler
  "(defn name [request] (when-ws-request request conn-name body))"
  {:arglists '([name [request] conn-name & body])}
  [name & sigs]
  (let [[name [[request] conn-name & body]]
        (macro/name-with-attributes name sigs)]
    `(defn ~name [~request] (when-ws-request ~request ~conn-name ~@body))))