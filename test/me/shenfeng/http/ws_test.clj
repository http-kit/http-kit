(ns me.shenfeng.http.ws-test
  (:use clojure.test
        (compojure [core :only [defroutes GET POST HEAD DELETE ANY context]]
                   [handler :only [site]]
                   [route :only [not-found]])
        me.shenfeng.http.server)
  (:require [clj-http.client :as http]
            [me.shenfeng.http.client :as client]
            [clj-http.util :as u])
  (:import me.shenfeng.http.ws.WebSocketClient))

(defn- string-80k []
  (apply str (map char
                  (take (* 8 1024)                ; 80k
                        (apply concat (repeat (range (int \a) (int \z))))))))

;; [a..z]+
(def const-string                       ; 8M string
  (let [tmp (string-80k)]
    (apply str (repeat 1024 tmp))))

(defn ws-handler [req]
  (when-ws-request req con
                   (on-mesg con (fn [msg]
                                  (try
                                    (let [{:keys [length times]} (read-string msg)]
                                      (doseq [_ (range 0 times)]
                                        (send-mesg con (subs const-string 0 length))))
                                    (catch Exception e
                                      (println e)
                                      (send-mesg con msg)))))))

(defroutes test-routes (GET "/ws" [] ws-handler))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (site test-routes) {:port 4348})]
                        (try (f) (finally (server))))))

(deftest test-websocket
  (let [client (WebSocketClient. "ws://localhost:4348/ws")]
    (doseq [_ (range 0 10)]
      (let [length (rand-int (* 4 1024 1024))
            times (rand-int 10)]
        ;; ask for a given length, make sure server understand it
        (.sendMessage client (pr-str {:length length :times times}))
        (doseq [_ (range 0 times)]
          (is (= length (count (.getMessage client)))))))
    (.close client))) ;; server's closeFrame response is checked

(deftest test-websocket-fragmented
  (let [client (WebSocketClient. "ws://localhost:4348/ws")]
    (doseq [_ (range 0 10)]
      (let [length (min 100 (rand-int 10024))
            sent (pr-str {:length length :times 2
                          :text (subs const-string 0 length)})]
        ;; ask for a given length, make sure server understand it
        (.sendFragmentedMesg client sent)
        (doseq [_ (range 0 2)]
          (let [r (.getMessage client)]
            (when (not= (count r) length)
              (println (str "sent:\n" sent
                            "\n---------------------------------"
                            "\nreceive:\n" r))
              (is false))))
        (let [d (subs const-string 0 120)]
          (= d (.ping client d)))))
    (.close client)))

;; test many times, and connect result
;; rm /tmp/test_results&& ./scripts/javac with-test && for i in {1..100}; do lein test me.shenfeng.http.ws-test >> /tmp/test_results; done