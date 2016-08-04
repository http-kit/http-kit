(ns org.httpkit.num-connections
  (:require  [clojure.test :as t :refer [deftest is]]
             [org.httpkit.server :refer [run-server]]
             [org.httpkit.client :as client]))

(deftest one-connection
  (let [client  (org.httpkit.client.HttpClient. 1) ; Max 1 conn
        p       (promise)
        handler (fn [req]
                  (if (= (:uri req) "/wait")
                    {:status @p}
                    {:status 200}))
        server (run-server handler {:port 4347})
        r1 (client/get "http://localhost:4347/wait" {:client client})
        r2 (client/get "http://localhost:4347"      {:client client})]

    (try
      (Thread/sleep 100)
      (is (not (realized? r1)))
      (is (not (realized? r2))) ; Waiting till r1 completes
      (deliver p 200) ; Complete r1
      (Thread/sleep 100)
      (is (realized? r1))
      (is (realized? r2))
      (finally (server)))))

(deftest many-connections
  (let [client  (org.httpkit.client.HttpClient.) ; Unlimited conns
        p       (promise)
        handler (fn [req]
                  (if (= (:uri req) "/wait")
                    {:status @p}
                    {:status 200}))
        server (run-server handler {:port 4347})
        r1 (client/get "http://localhost:4347/wait" {:client client})
        r2 (client/get "http://localhost:4347"      {:client client})]

    (try
      (Thread/sleep 100)
      (is (not (realized? r1)))
      (is      (realized? r2)) ; Not waiting till r1 completes
      (deliver p 200) ; Complete r1
      (Thread/sleep 100)
      (is (realized? r1))
      (finally (server)))))
