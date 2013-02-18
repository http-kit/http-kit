(ns org.httpkit.benchmark
  (:use org.httpkit.server
        org.httpkit.test-util
        [clojure.tools.cli :only [cli]]))

(defn handler [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (subs const-string 0 1024)})

(defn -main [& args]
  (let [[options _ banner]
        (cli args
             ["-p" "--port" "Port to listen" :default 9090 :parse-fn to-int]
             ["--[no-]help" "Print this help"])]
    (when (:help options) (println banner) (System/exit 0))
    (run-server handler {:port (options :port)})
    (println (str "listen on port :" (options :port)))))
