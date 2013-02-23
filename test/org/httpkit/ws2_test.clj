(ns org.httpkit.ws2-test
  (:use clojure.test
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]])
        org.httpkit.test-util
        org.httpkit.server)
  (:require [clj-http.client :as http]
            [org.httpkit.client :as client]
            [http.async.client :as h]
            [clj-http.util :as u])
  (:import org.httpkit.ws.WebSocketClient))

(defn handler [req]
  (ws-response req ch
               (on-receive ch (fn [mesg]
                                (send! ch (update-in mesg [:id] inc))))))

(use-fixtures :once (fn [f]
                      ;; sent hook take 3 params
                      (set-global-hook (fn [data websocket? first-send?]
                                         (pr-str data)) read-string)
                      (let [server (run-server handler {:port 4348})]
                        (try (f) (finally
                                  (set-global-hook identity identity)
                                  (server))))))

(deftest test-send-receive-hook
  (let [client (WebSocketClient. "ws://localhost:4348/ws")]
    (doseq [id (range 0 10)]
      (.sendMessage client (pr-str {:id id}))
      (is (= (inc id) (:id (read-string (.getMessage client))))))
    (.close client)))
