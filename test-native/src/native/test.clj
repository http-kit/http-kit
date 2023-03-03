(ns native.test
  (:require [org.httpkit.client :as client]
            [org.httpkit.server :as server]
            [org.httpkit.sni-client :as sni-client]
            [clojure.test :as t])
  (:gen-class))

(defn -main [& _args]
  (let [!server (atom nil)]
    (try (let [server (server/run-server (fn [_]
                                           {:body "response"
                                            :status 200
                                            :headers {"content-type" "text/plain"}})
                                         {:port 12233
                                          :legacy-return-value? false})]
           (reset! !server server)
           (t/is (= "response" (:body @(client/get "http://localhost:12233"))))
           (t/is (= "response" (:body @(client/get "http://localhost:12233" {:client @sni-client/default-client}))))
           (println "Native test succesful!"))
         (catch Exception e
           (println e) (System/exit 1))
         (finally
           (server/server-stop! @!server)
           (shutdown-agents)))))
