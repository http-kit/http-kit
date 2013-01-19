(ns me.shenfeng.http.server.benchmark
  (:use ring.adapter.jetty
        me.shenfeng.http.server
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]])
        [clojure.tools.cli :only [cli]]))

(defn handler [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (list "Hello World", "What are you doing")})

(defroutes api-routes
  (GET "/" [] handler)
  (GET "/other" [] handler)
  (GET "/goog/:sdfsdf/:sdfsdf/sdfsd" [] handler)
  (GET "/api/:version/what" [] handler))

(defn start-server [{:keys [server]}]
  (let [handler (-> api-routes site)]
    (case server
      :home
      (run-server handler {:port 9091 :thread 6})
      :jetty
      (run-jetty handler {:port 9091 :join? false}))))

(defn -main [& args]
  (let [[options _ banner]
        (cli args
             ["-s" "--server" "jetty | netty | home"
              :default :home :parse-fn keyword]
             ["--[no-]help" "Print this help"])]
    (when (:help options) (println banner) (System/exit 0))
    (start-server options)))
