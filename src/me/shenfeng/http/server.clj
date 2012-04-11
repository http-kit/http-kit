(ns me.shenfeng.http.server
  (:import me.shenfeng.http.server.HttpServer
           me.shenfeng.http.server.Handler))

(defn run-server [handler {:keys [port thread ip] :as options
                           :or {ip "0.0.0.0" thread 2}}]
  (let [h (Handler. thread handler)
        s (HttpServer. ip port h)]
    (.start s)
    (fn [] (.close h) (.stop s))))
