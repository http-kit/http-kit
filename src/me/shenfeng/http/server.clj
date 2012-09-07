(ns me.shenfeng.http.server
  (:import [me.shenfeng.http.server HttpServer IListenableFuture Handler]))

(defn run-server [handler {:keys [port thread ip max-body] :as options
                           :or {ip "0.0.0.0" port 8090
                                thread 2 max-body 20480}}]
  (let [h (Handler. thread handler)
        s (HttpServer. ip port h max-body)]
    (.start s)
    (fn [] (.close h) (.stop s))))

(defmacro defasync [name params & body]
  (let [req (params 0)]
    `(defn ~name [~req]
       {:status 200
        :body (let [data# (atom {})
                    ~req (assoc ~req :cb
                                (fn [resp#]
                                  (reset! data# (assoc @data# :r resp#))
                                  (when-let [l# ^Runnable (:l @data#)]
                                    (.run l#))))]
                (do ~@body)
                (reify IListenableFuture
                  (addListener [this# listener#]
                    (if-let [d# (:r @data#)]
                      (.run ^Runnable listener#)
                      (reset! data# (assoc @data# :l listener#))))
                  (get [this#]
                    (:r @data#))))})))
