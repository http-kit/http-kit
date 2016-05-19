(ns org.httpkit.num-connections
  (:require  [clojure.test :as t :refer [deftest is]]
             [org.httpkit.server :refer [run-server]]
             [org.httpkit.client :as client]))

(defn h [p]
  (fn [r]
    (if (= (:uri r) "/wait")
      {:status @p}
      {:status 200})))

(deftest one-connection
  (let [p (promise)
        c (org.httpkit.client.HttpClient. 1)
        s (run-server (h p) {:port 4347})
        r1 (client/get "http://localhost:4347/wait" {:client c})
        r2 (client/get "http://localhost:4347" {:client c})]
    (try
      (is (not (realized? r1)))
      (is (not (realized? r2)))
      (deliver p 200)
      (Thread/sleep 100)
      (is (realized? r1))
      (Thread/sleep 100)
      (is (realized? r2))
      (finally (s)))))
