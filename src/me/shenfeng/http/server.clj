(ns me.shenfeng.http.server
  (:import [me.shenfeng.http.server HttpServer IListenableFuture RingHandler]
           me.shenfeng.http.ws.WsCon
           javax.xml.bind.DatatypeConverter
           java.security.MessageDigest))

;;;; Ring server

(defn run-server
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

(defmacro defasync [name [request] callback-name & body]
  `(defn ~name [~request]
     {:status 200
      :headers {}
      :body (let [data# (atom {})
                  ~callback-name
                  (fn [response#]
                    (reset! data# (assoc @data# :response response#))
                    (when-let [listener# ^Runnable (:listener @data#)]
                      (.run listener#)))]
              (do ~@body)
              (reify IListenableFuture
                (addListener [this# listener#]
                  (if-let [d# (:response @data#)]
                    (.run ^Runnable listener#)
                    (reset! data# (assoc @data# :listener listener#))))
                (get [this#] (:response @data#))))}))

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

(defmacro defwshandler [name [request] conn-name & body]
  `(defn ~name [~request]
     (let [~conn-name (:websocket ~request)
           key# (get-in ~request [:headers "sec-websocket-key"])]
       (if (and ~conn-name key#)
         (do ~@body
             {:status 101
              :headers {"Upgrade" "websocket"
                        "Connection" "Upgrade"
                        "Sec-WebSocket-Accept" (accept key#)
                        ;; "Sec-WebSocket-Protocol" "13"
                        }})
         {:status 400
          :headers {}
          :body "Websocket expected"}))))