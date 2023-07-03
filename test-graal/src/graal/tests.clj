(ns graal.tests
  (:require
   [org.httpkit.client     :as hk-client]
   [org.httpkit.server     :as hk-server]
   [org.httpkit.sni-client :as sni-client]
   [clojure.test           :as test :refer [is]])
  (:gen-class))

(defn -main [& _args]
  (let [server_ (atom nil)]
    (try
      (reset! server_
        (hk-server/run-server
          (fn [_ring-req]
            {:status  200
             :body    "response"
             :headers {"content-type" "text/plain"}})
          {:port 12233
           :legacy-return-value? false}))

      (is (= "response" (:body @(hk-client/get "http://localhost:12233"))))
      (is (= "response" (:body @(hk-client/get "http://localhost:12233"
                                  {:client @sni-client/default-client}))))

      (println "Graal tests succesful!")

      (catch Throwable t
        (println t)
        (System/exit 1))

      (finally
        (hk-server/server-stop! @server_)
        (shutdown-agents)))))
