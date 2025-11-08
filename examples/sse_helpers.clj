(ns sse-helpers
  "Higher-level SSE handler helpers for http-kit"
  (:require
   [org.httpkit.server :refer [as-channel send!]]))

(defn sse-response
  "Returns an SSE async channel response from within your handler.

  Options:
    :on-open  - (fn [send-fn!]) called when client connects.
                send-fn! is a function that sends SSE data, returns true if sent.
    :on-close - (fn [status]) called when client disconnects."
  [request {:keys [on-open on-close]}]
  (as-channel request
    {:on-open
     (fn [ch]
       ;; Send SSE headers first
       (send! ch
         {:status 200
          :headers {"Content-Type" "text/event-stream"
                    "Cache-Control" "no-cache, no-store"
                    "Connection" "keep-alive"}}
         false)

       ;; Create send function that handles all the checks
       (let [send-fn! (fn [data]
                        ;; Returns true if sent, false if channel closed
                        (send! ch {:body data} false))]

         ;; Call user's on-open with the send function
         (when on-open
           (on-open send-fn!))))

     :on-close
     (fn [ch status]
       (when on-close
         (on-close status)))}))

;; Example: Basic usage
;;
;; (defn my-handler [request]
;;   (if (authenticated? request)
;;     (sse-response request
;;       {:on-open (fn [send-fn!]
;;                   (send-fn! "data: hello\n\n")
;;                   (future
;;                     (loop [i 0]
;;                       (Thread/sleep 1000)
;;                       (when (send-fn! (format "data: Event #%d\n\n" i))
;;                         (recur (inc i))))))
;;        :on-close (fn [status]
;;                    (println "Client disconnected:" status))})
;;     {:status 401 :body "Unauthorized"}))

(defn sse-event
  "Formats data as an SSE event string.

  Options:
    :data  - the event data (required)
    :event - event type (optional)
    :id    - event ID (optional)
    :retry - retry timeout in ms (optional)

  Example:
    (sse-event {:data \"hello\"})
    => \"data: hello\\n\\n\"

    (sse-event {:event \"update\" :id 42 :data \"world\"})
    => \"event: update\\nid: 42\\ndata: world\\n\\n\""
  [{:keys [data event id retry]}]
  (str
    (when event (str "event: " event "\n"))
    (when id (str "id: " id "\n"))
    (when retry (str "retry: " retry "\n"))
    "data: " data "\n\n"))

(comment
  ;; Example usage
  (require '[org.httpkit.server :refer [run-server]]
           '[org.httpkit.timer :refer [schedule-task]])

  (defn my-handler [request]
    (case (:uri request)
      "/events"
      (sse-response request
        {:on-open
         (fn [send-fn!]
           (println "Client connected")

           ;; Send initial event
           (send-fn! (sse-event {:data "Connected!"}))

           ;; Start broadcasting with recursion
           (let [counter (atom 0)]
             ((fn broadcast []
                (when (send-fn! (sse-event
                                  {:id @counter
                                   :data (str "Event #" (swap! counter inc))}))
                  (schedule-task 1000 (broadcast)))))))

         :on-close
         (fn [status]
           (println "Client disconnected:" status))})

      ;; Regular response for other routes
      {:status 200 :body "Use /events for SSE"}))

  ;; Start server
  (def server (run-server my-handler {:port 8080}))

  ;; Stop server
  (server)
  )
