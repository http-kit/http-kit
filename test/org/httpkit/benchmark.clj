(ns org.httpkit.benchmark
  (:require
   [org.httpkit.server    :as hks]
   [org.httpkit.test-util :as tu]
   [clojure.tools.cli :refer [cli]]))

(defn handler [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "hello world"})

(defn -main
  "Start server for benching.
  Tests with `^:benchmark` metadata will be run."
  [& args]

  (let [[options _ banner]
        (cli args
          ["-p" "--port" "Port to listen" :default 9090 :parse-fn tu/to-int]
          ["--[no-]help" "Print this help"])]

    (when (:help options) (println banner) (System/exit 0))
    (hks/run-server handler {:port   (options :port)})
    (println (str "Listening on port :" (options :port)))))
