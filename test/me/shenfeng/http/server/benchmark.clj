(ns me.shenfeng.http.server.benchmark
  (:use ring.adapter.jetty
        me.shenfeng.http.server
        [clojure.tools.cli :only [cli]]
        ring.adapter.netty))

(def resp {:status  200
           :headers {"Content-Type" "text/plain"}
           :body    "Hello World"})

(defn start-server [{:keys [server]}]
  (let [handler (fn [req] resp)]
    (case server
      :netty
      (run-netty handler {:port 9091 :worder 6}) ; 6 worker; threads
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

