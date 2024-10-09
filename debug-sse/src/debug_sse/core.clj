(ns debug-sse.core
  (:require
   [org.httpkit.server :as hk]))

(defonce clients (atom #{}))

(defn format-event [body] (str "data: " body "\n\n"))
(defn send! [ch message]
  (hk/send! ch
    {:body   (format-event message)
     :status 200
     :headers
     {"Content-Type"  "text/event-stream"
      "Cache-Control" "no-cache, no-store"}}
    false))

(let [idx_ (volatile! 0)]
  (defn handler-sse [req]
    (let [idx (vswap! idx_ inc)]
      (println (str ">>> Clojure handler request " idx))
      (hk/as-channel req
        {:on-open  (fn [ch]   (println (str ">>> Clojure handler :on-open "  idx)) (swap! clients conj ch))
         :on-close (fn [ch _] (println (str ">>> Clojure handler :on-close " idx)) (swap! clients disj ch))}))))

(defn broadcast-message-to-connected-clients! [message]
  (run! (fn [ch] (send! ch message)) @clients))

(def app
  (fn handler  [{:keys [request-method uri] :as req}]
    (if (= [:get  "/"] [request-method uri])
      (handler-sse req)
      {:status 404})))

(comment
  (def server (hk/run-server #'app {:port 8080}))
  (server) ; stop server

  ;; 1. Open a terminal and connect
  ;; curl localhost:8080 -vv

  ;; 2. Send some messages you should see them in your
  ;; terminal window
  (broadcast-message-to-connected-clients! "HELLO")

  ;; 3. You should see your client connected in here
  @clients

  ;; 4. Close the terminal

  ;; 5. Send a bunch of messages
  ;; in case there's buffering going on 
  (broadcast-message-to-connected-clients! "HELLO")

  ;; 6. Your client should be gone from the list as
  ;; the send should fail and close the channel
  @clients

  ;; 7. But instead the channels remain open

  ;; you can also run send over the connections
  ;; and they return true, which means message send successful.
  (mapv (fn [ch] (send! ch "Hello")) @clients)
  ;; but the message send should fail as the connection has been
  ;; closed by the client

  )

(defn -main [& args]
  (println "Running http-kit for 5 secs")
  (let [s (hk/run-server #'app {:port 8080})]
    (Thread/sleep 5000)
    (s)
    (System/exit 0)))
