(ns sse-demo
  "Simple SSE server demo that broadcasts to one client at a time"
  (:require
   [org.httpkit.server :refer [run-server as-channel send! open?]]
   [org.httpkit.timer :refer [schedule-task]]))

(defn sse-handler
  "Handles SSE connection - broadcasts events until client disconnects"
  [request]
  (as-channel request
    {:on-open
     (fn [ch]
       (println "SSE client connected")

       ;; Send initial response with SSE headers
       (send! ch
         {:status 200
          :headers {"Content-Type" "text/event-stream"
                    "Cache-Control" "no-cache, no-store"
                    "Connection" "keep-alive"}}
         false) ; false = don't close connection

       ;; Start broadcasting events
       (let [event-id (atom 0)]
         ;; Recursive function to send events
         ((fn broadcast []
            (when (open? ch)  ; Stops automatically when client disconnects
              (let [id (swap! event-id inc)
                    timestamp (java.time.Instant/now)]
                (send! ch
                  {:body (format "id: %d\ndata: Event #%d at %s\n\n"
                                 id id timestamp)}
                  false)
                (println (format "Sent event #%d to client" id))

                ;; Schedule next event in 1 second
                (schedule-task 1000 (broadcast))))))))

     :on-close
     (fn [ch status]
       (println "SSE client disconnected, status:" status)
       ;; Channel is already closed, no cleanup needed
       ;; The open? check in broadcast will stop the loop
       )}))

(defn -main [& args]
  (let [port 8080]
    (run-server sse-handler {:port port})
    (println (format "SSE server running on http://localhost:%d" port))
    (println "Test with: curl -N http://localhost:8080")
    (println "Press Ctrl+C to stop")))

(comment
  ;; Start the server
  (def server (-main))

  ;; Stop the server
  (server)
  )
