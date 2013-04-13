(ns org.httpkit.benchmark
  (:use org.httpkit.server
        org.httpkit.test-util
        [clojure.tools.cli :only [cli]]))

(defn handler [req]
  {:status 200
   :headers {"Content-Type" "text/plain"
             "X-header" "美味书签"}
   ;; jdk 6 is slow here, jdk7 is fine. String implemented differently
   :body "hello world"})

;;; extreme case.
;;; more real world, see server_test.clj
(defn -main [& args]
  (let [[options _ banner]
        (cli args
             ["-p" "--port" "Port to listen" :default 9090 :parse-fn to-int]
             ["--[no-]help" "Print this help"])]
    (when (:help options) (println banner) (System/exit 0))
    (run-server handler {:port (options :port)})
    (println (str "listen on port :" (options :port)))))
