(ns me.shenfeng.http.server
  (:import [me.shenfeng.http.server HttpServer IListenableFuture RingHandler]
           me.shenfeng.http.ws.WsCon
           javax.xml.bind.DatatypeConverter
           java.security.MessageDigest))

(defn run-server [handler {:keys [port thread ip max-body] :as options
                           :or {ip "0.0.0.0" port 8090
                                thread 2 max-body 20480}}]
  (let [h (RingHandler. thread handler)
        s (HttpServer. ip port h max-body)]
    (.start s)
    (fn [] (.close h) (.stop s))))

(defmacro defasync [name [req] cb & body]
  `(defn ~name [~req]
     {:status 200
      :headers {}
      :body (let [data# (atom {})
                  ~cb (fn [resp#]
                        (reset! data# (assoc @data# :r resp#))
                        (when-let [l# ^Runnable (:l @data#)]
                          (.run l#)))]
              (do ~@body)
              (reify IListenableFuture
                (addListener [this# listener#]
                  (if-let [d# (:r @data#)]
                    (.run ^Runnable listener#)
                    (reset! data# (assoc @data# :l listener#))))
                (get [this#]
                  (:r @data#))))}))

(defn accept [key]
  (let [md (MessageDigest/getInstance "SHA1")
        websocket-13-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"]
    (DatatypeConverter/printBase64Binary
     (.digest md (.getBytes ^String (str key websocket-13-guid))))))

(defn receive [^WsCon con fn]
  (.addListener con fn))

(defn write [^WsCon con msg]
  (.send con msg))

(defmacro defwshandler [name [req] con & body]
  `(defn ~name [~req]
     (let [~con (:websocket ~req)
           key# (get-in ~req [:headers "sec-websocket-key"])]
       (if (and ~con key#)
         (do ~@body
             {:status 101
              :headers {"Upgrade" "websocket"
                        "Connection" "Upgrade"
                        "Sec-WebSocket-Accept" (accept key#)
                        ;; "Sec-WebSocket-Protocol" "13"
                        }})
         {:status 400
          :headers {}
          :body "websocket expected"}))))
