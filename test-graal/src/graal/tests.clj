(ns graal.tests
  (:require
   [org.httpkit.client     :as hk-client]
   [org.httpkit.server     :as hk-server]
   [org.httpkit.sni-client :as sni-client])
  (:gen-class))

(defn- check= [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Graal smoke-test failure"
             {:expected expected, :actual actual}))))

(defn -main [& _args]
  (let [server_ (atom nil)]
    (try
      (reset! server_
        (hk-server/run-server
          (fn [_ring-req]
            {:status  200
             :body    "response"
             :headers {"content-type" "text/plain"}})
          {:port 0
           :legacy-return-value? false}))

      (let [url (str "http://localhost:" (hk-server/server-port @server_))]
        (check= "response" (:body @(hk-client/get url)))
        (check= "response" (:body @(hk-client/get url
                                     {:client @sni-client/default-client}))))

      (println "Graal tests successful!")

      (catch Throwable t
        (.printStackTrace t)
        (throw t))

      (finally
        (when-let [server @server_]
          (hk-server/server-stop! server))
        (shutdown-agents)))))
