(ns org.httpkit.proxy-test
  (:use [org.httpkit.server :only [run-server as-channel send!]]
        [org.httpkit.client :only [request]]))

(defn- proxy-opts [req]
  {:url (str "http://192.168.1.101:9090" (:uri req)
             (if-let [q (:query-string req)]
               (str "?" q)
               ""))
   :timeout 30000 ;ms
   :method (:request-method req)
   :headers (assoc (:headers req)
                   "X-Forwarded-For" (:remote-addr req))
   :body (:body req)})

(defn handler [req]
  (as-channel req
    (request (proxy-opts req)
             (fn [channel {:keys [status headers body error]}]
               (if error
                 (send! channel {:status 503
                                 :headers {"Content-Type" "text/plain"}
                                 :body (str "Cannot access backend\n" error)})
                 (send! channel {:status status
                                 :headers (zipmap (map name (keys headers)) (vals headers))
                                 :body body}))))))

(defn -main [& args]
  (run-server handler {:port 8080})
  (println "proxy server started at 0.0.0.0@8080"))
