(ns sse-helper-demo
  "Demo using the SSE helper"
  (:require
   [org.httpkit.server :refer [run-server]]
   [org.httpkit.timer :refer [schedule-task]]
   [sse-helpers :refer [create-sse-handler sse-event]]))

(defn my-sse-handler
  "SSE handler using the high-level helper"
  []
  (create-sse-handler
    {:on-open
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
     (fn [status]
       (println "Client disconnected, status:" status))}))

(defn -main [& args]
  (let [port 8080]
    (run-server (my-sse-handler) {:port port})
    (println (format "SSE server running on http://localhost:%d" port))
    (println "Test with: curl -N http://localhost:8080")
    (println "Press Ctrl+C to stop")))

(comment
  ;; Start the server
  (def server (-main))

  ;; Stop the server
  (server)
  )
