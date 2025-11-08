(ns sse-helper-demo
  "Demo using the SSE helper"
  (:require
   [org.httpkit.server :refer [run-server]]
   [org.httpkit.timer :refer [schedule-task]]
   [sse-helpers :refer [sse-response sse-event]]))

(defn my-handler
  "Handler that returns SSE response with compression and error handling"
  [request]
  (sse-response request
    {:compress? true  ; Enable gzip compression

     :on-open
     (fn [send-fn!]
       (println "Client connected")

       ;; Send welcome message
       (send-fn! (sse-event {:data "Welcome! Starting event stream..."}))

       ;; Start broadcasting - send-fn! returns false when channel closes
       (let [counter (atom 0)]
         ((fn broadcast []
            (let [id (swap! counter inc)
                  timestamp (java.time.Instant/now)]
              (when (send-fn! (sse-event
                                {:id id
                                 :data (str "Event #" id " at " timestamp)}))
                ;; send-fn! returned true, channel still open
                (println "Sent event" id)
                (schedule-task 1000 (broadcast))))))))

     :on-close
     (fn [reason]
       (case reason
         :send-failed (println "Client disconnected: send failed")
         :exception   (println "Connection closed due to exception")
         (println "Client disconnected, status:" reason)))

     :on-exception
     (fn [e]
       (println "Error while sending SSE event:" (.getMessage e)))}))

(defn -main [& args]
  (let [port 8080]
    (run-server my-handler {:port port})
    (println (format "SSE server running on http://localhost:%d" port))
    (println "Test with: curl -N http://localhost:8080")
    (println "Press Ctrl+C to stop")))

(comment
  ;; Start the server
  (def server (-main))

  ;; Stop the server
  (server)
  )
