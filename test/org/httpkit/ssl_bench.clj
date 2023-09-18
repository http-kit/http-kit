(ns org.httpkit.ssl-bench
  (:use org.httpkit.test-util
        (compojure [core :only [defroutes GET]]
                   [handler :only [site]])
        org.httpkit.server)
  (:require [org.httpkit.client :as http]))

(defn ssl-handler [req]
  (as-channel req
    (let [length (to-int (or (-> req :params :length) "1024"))
          opts {:insecure? true
                :keepalive (rand-nth [-1 120000 1200 100])}]
      (http/get (str "https://localhost:9898/length?length=" length) opts
                (fn [channel {:keys [status body headers error opts]}]
                  (when-not (== (count body) length)
                    (println "error, expect: " length "; but get: " (count body)))
                  (send! channel (str (count body))))))))

(defn proxy-handler [req]
  (as-channel req
    (let [length (to-int (or (-> req :params :length) "1024"))]
      (http/get (str "http://localhost:9090/length?length=" length)
                (fn [channel {:keys [status body headers error opts]}]
                  (when-not (== (count body) length)
                    (println "error, expect: " length "; but get: " (count body)))
                  (send! channel (str (count body))))))))

(defroutes test-routes
  (GET "/" [] ssl-handler)
  (GET "/proxy" [] proxy-handler))

(defn -main [& args]
  (run-server (site test-routes) {:port 8080})
  (println "server started at 0.0.0.0:8080"))
