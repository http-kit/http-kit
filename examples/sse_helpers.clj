(ns sse-helpers
  "Higher-level SSE handler helpers for http-kit"
  (:require
   [org.httpkit.server :refer [as-channel send! close]])
  (:import
   [java.io ByteArrayOutputStream]
   [java.util.zip GZIPOutputStream]))

(defn- gzip-compress
  "Compress string data using gzip"
  [^String data]
  (let [baos (ByteArrayOutputStream.)
        gzip (GZIPOutputStream. baos)]
    (.write gzip (.getBytes data "UTF-8"))
    (.close gzip)
    (.toByteArray baos)))

(defn sse-response
  "Returns an SSE async channel response from within your handler.

  Options:
    :on-open      - (fn [send-fn!]) called when client connects.
                    send-fn! is a function that sends SSE data, returns true if sent.
    :on-close     - (fn [reason]) called when connection closes.
                    reason can be:
                      - :send-failed (send returned false, client likely disconnected)
                      - :exception (exception during send)
                      - http-kit status keyword (:server-close, :client-close, etc.)
    :on-exception - (fn [exception]) called when send throws an exception.
    :compress?    - boolean, enable gzip compression (default false)."
  [request {:keys [on-open on-close on-exception compress?]}]
  (let [close-reason (atom nil)]
    (as-channel request
      {:on-open
       (fn [ch]
         ;; Send SSE headers with optional compression
         (send! ch
           {:status 200
            :headers (cond-> {"Content-Type" "text/event-stream"
                              "Cache-Control" "no-cache, no-store"
                              "Connection" "keep-alive"}
                       compress? (assoc "Content-Encoding" "gzip"))}
           false)

         ;; Create send function with error handling and auto-close
         (let [send-fn! (fn [data]
                          (try
                            (let [body (if compress?
                                         (gzip-compress data)
                                         data)
                                  sent? (send! ch {:body body} false)]

                              (when (and (not sent?) (nil? @close-reason))
                                ;; Send failed, client likely disconnected
                                (reset! close-reason :send-failed)
                                (close ch))

                              sent?)

                            (catch Exception e
                              ;; Notify about exception
                              (when on-exception
                                (on-exception e))

                              ;; Mark reason and close
                              (when (nil? @close-reason)
                                (reset! close-reason :exception))
                              (close ch)

                              false)))]

           ;; Call user's on-open with the send function
           (when on-open
             (on-open send-fn!))))

       :on-close
       (fn [ch status]
         ;; Use our tracked reason if we closed due to send failure/exception,
         ;; otherwise use http-kit's status
         (let [reason (or @close-reason status)]
           (when on-close
             (on-close reason))))})))

;; Example: Basic usage with error handling
;;
;; (defn my-handler [request]
;;   (if (authenticated? request)
;;     (sse-response request
;;       {:compress? true  ; Enable gzip compression
;;        :on-open (fn [send-fn!]
;;                   (send-fn! "data: hello\n\n")
;;                   (future
;;                     (loop [i 0]
;;                       (Thread/sleep 1000)
;;                       (when (send-fn! (format "data: Event #%d\n\n" i))
;;                         (recur (inc i))))))
;;        :on-close (fn [reason]
;;                    (case reason
;;                      :send-failed (println "Client disconnected (send failed)")
;;                      :exception   (println "Closed due to exception")
;;                      (println "Closed:" reason)))
;;        :on-exception (fn [e]
;;                        (println "Error sending SSE:" (.getMessage e)))})
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
