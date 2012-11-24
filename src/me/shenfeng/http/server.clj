(ns me.shenfeng.http.server
  (:import [me.shenfeng.http.server HttpServer IListenableFuture RingHandler]))

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
