(ns org.httpkit.ws-test
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
  (:import [org.httpkit.ws WebSocketClient]
           org.httpkit.SpecialHttpClient))

(defn ws-handler [req]
  (with-channel req con
    (on-close con close-handler)
    (on-receive con (fn [msg]
                      (try
                        (let [{:keys [length times]} (read-string msg)]
                          (doseq [_ (range 0 times)]
                            (send! con (subs const-string 0 length))))
                        (catch Exception e
                          (println e)
                          (send! con msg)))))))

(defn ws-handler-sent-on-connect [req]
  (with-channel req con
    (send! con "hello") ;; should sendable when on-connet
    (send! con "world")
    (on-receive con (fn [mesg]
                      ;; only :body is picked
                      (send! con {:body mesg}))))) ; echo back

(defn ws-handler-async-client [req] ;; test with http.async.client, echo back
  (with-channel req con
    (on-receive con (fn [mesg]
                      (send! con mesg)))))

(defn binary-ws-handler [req]
  (with-channel req con
    (on-receive con (fn [data]
                      (let [retdata (doto (aclone ^bytes data) (java.util.Arrays/sort))
                            data (if (rand-nth [true false])
                                   (java.io.ByteArrayInputStream. retdata)
                                   retdata)]
                        (send! con data))))))

(defn messg-order-handler [req]
  (with-channel req con
    (let [mesg-idx (atom 0)
          h (fn [mesg]
              (let [id (swap! mesg-idx inc)
                    i (:id (read-string mesg))]
                (send! con (str (= id i)))))]
      (on-receive con h))))

(defn not-interleave-handler [req]
  (with-channel req con
    (on-receive con
                (fn [mesg]
                  (let [total (to-int mesg)]
                    (doall (pmap (fn [length idx]
                                   (let [length (+ length 1025)
                                         c (char (+ (int \0) (rem length 30)))]
                                     ;; (Thread/sleep (rand-int (* 10 total)))
                                     (send! con (apply str (concat (take 4 (concat
                                                                            (str idx)
                                                                            (repeat \0)))
                                                                   (repeat length c))))))
                                 (repeatedly total (partial rand-int (* 1024 1024)))
                                 (range 10 1000))))))))

(defroutes test-routes
  (GET "/ws" [] ws-handler)
  (GET "/sent-on-connect" [] ws-handler-sent-on-connect)
  (GET "/echo" [] ws-handler-async-client)
  (GET "/http-async-client" [] ws-handler-async-client)
  (GET "/binary" [] binary-ws-handler)
  (GET "/interleaved" [] not-interleave-handler)
  (GET "/order" [] messg-order-handler))

(use-fixtures :once (fn [f]
                      (let [server (run-server
                                    (site test-routes) {:port 4348})]
                        (try (f) (finally (server))))))

(comment (def server (run-server (site test-routes) {:port 4348}))
         (def client1 (WebSocketClient. "ws://localhost:4348/ws")))

(deftest test-websocket
  (doseq [_ (range 1 4)]
    (let [client (WebSocketClient. "ws://localhost:4348/ws")]
      (doseq [_ (range 0 10)]
        (let [length (rand-int (* 4 1024 1024))
              times (rand-int 10)]
          ;; ask for a given length, make sure server understand it
          (.sendMessage client (pr-str {:length length :times times}))
          (doseq [_ (range 0 times)]
            (is (= length (count (.getMessage client)))))))
      (.close client) ;; server's closeFrame response is checked
      ;; see test_util.clj
      (check-on-close-called))))

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
          (= d (.ping client d))
          (= d (.pong client d)))))
    (.close client)))

(deftest test-sent-message-in-body      ; issue #14
  (let [client (WebSocketClient. "ws://localhost:4348/sent-on-connect")]
    (is (= "hello" (.getMessage client)))
    (is (= "world" (.getMessage client)))
    (doseq [idx (range 0 3)]
      (let [mesg (str "message#" idx)]
        (.sendMessage client mesg)
        (is (= mesg (.getMessage client))))) ;; echo expected
    (.close client)))

(deftest test-tcp-segmented-frame-does-right  ; issue #47
  (let [data (slurp "test/ws_unmask_bug_47.txt") ; 65 data, segement sure, since receive buffer is 64K
        data_3 (str data data data)
        client (WebSocketClient. "ws://localhost:4348/echo")]
    (dotimes [_ 3]
      (.sendFragmentedMesg client data_3 3)
      (is (= data_3 (.getMessage client)))
      (.sendMessage client data)
      (is (= data (.getMessage client))))))

;; client can sent a byte a time
;; https://github.com/http-kit/http-kit/issues/80
(deftest test-slow-client
  (is (SpecialHttpClient/slowWebSocketClient "ws://localhost:4348/echo")))

(deftest test-binary-frame
  (let [client (WebSocketClient. "ws://localhost:4348/binary")]
    (dotimes [_ 5]
      (let [length (min 1024 (rand-int 10024))
            data (byte-array length (take length (repeatedly #(byte (rand-int 126)))))
            sorted-data (doto (aclone data) (java.util.Arrays/sort))]
        (.sendBinaryData client data)
        (let [output (.getMessage client)]
          (is (java.util.Arrays/equals sorted-data output)))))
    (.close client)))

;;; make sure Message ordering is guaranteed
(deftest test-message-executed-in-order
  (doseq [_ (range 1 5)]
    (let [client (WebSocketClient. "ws://localhost:4348/order")]
      (doseq [id (range 1 10)]
        (.sendMessage client (pr-str {:id id}))
        (is (= "true" (.getMessage client))))
      (.close client)))
  (doseq [_ (range 1 5)]
    (let [client (WebSocketClient. "ws://localhost:4348/order")]
      ;; 10 concurrent message
      (doseq [id (range 1 10)]
        (.sendMessage client (pr-str {:id id})))
      (doseq [_ (range 1 10)]
        (is (= "true" (.getMessage client))))
      (.close client))))

(deftest test-message-are-not-interleaved
  ;; TODO when length is large, http-kit seem to drop some buffer.
  ;; The problem remains even if writen is done by a signle Thread
  ;; A bug of http-kit or JVM?
  ;; Not a issue for http, But maybe a issue for websocket:
  ;; If write many large chunks of messages to client using many threads concurrenly
  (let [client (WebSocketClient. "ws://localhost:4348/interleaved")
        length 10]
    (.sendMessage client (str length))
    (doseq [i (range 0 length)]
      (let [mesg ^String (.getMessage client)]
        (if mesg
          (let [idx (.substring mesg 0 4)
                mesg (.substring mesg 4)
                ch (first mesg)]
            (is (every? (fn [c] (= c ch)) mesg)))
          ;; fail
          (is (> (count mesg) 1024)))))
    (.close client)))

(deftest test-with-http.async.client
  (with-open [client (h/create-client)]
    (let [latch (promise)
          received-msg (atom nil)
          ws (h/websocket client "ws://localhost:4348/http-async-client"
                          :text (fn [con mesg]
                                  (reset! received-msg mesg)
                                  (deliver latch true))
                          :close (fn [con status]
                                   ;; (println "close:" con status)
                                   )
                          :open (fn [con]
                                  ;; (println "opened:" con)
                                  ))]
      ;; (h/send ws :byte (byte-array 10)) not implemented yet
      (let [msg "testing12"]
        (h/send ws :text msg)
        @latch
        (is (= msg @received-msg)))
      (h/close ws))))

;; ;; test many times, and connect result
;; ;; rm /tmp/test_results&& ./scripts/javac with-test && for i in {1..100}; do lein test org.httpkit.ws-test >> /tmp/test_results; done
