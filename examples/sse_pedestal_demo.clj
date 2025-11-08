(ns sse-pedestal-demo
  "SSE handler as a Pedestal interceptor using http-kit async channels"
  (:require
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :as interceptor]
   [org.httpkit.server :refer [as-channel send! open?]]
   [org.httpkit.timer :refer [schedule-task]]))

(def sse-interceptor
  "Pedestal interceptor that handles SSE connections via http-kit async channels"
  (interceptor/interceptor
    {:name ::sse-handler
     :enter
     (fn [context]
       ;; Extract the Ring request from Pedestal context
       (let [request (:request context)]
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
              (println "SSE client disconnected, status:" status))}))

       ;; Return context without response - async channel handles it
       context)}))

(defn routes
  []
  (route/expand-routes
    #{["/sse" :get [sse-interceptor] :route-name ::sse]
      ["/health" :get (fn [_] {:status 200 :body "OK"}) :route-name ::health]}))

(defn create-server
  [& {:keys [port] :or {port 8080}}]
  (http/create-server
    {::http/routes (routes)
     ::http/type   :jetty  ;; Default, but can be overridden to use http-kit
     ::http/port   port
     ::http/join?  false}))

(defn -main [& args]
  (let [server (-> (create-server :port 8080)
                   http/start)]
    (println "Pedestal SSE server running on http://localhost:8080/sse")
    (println "Test with: curl -N http://localhost:8080/sse")
    (println "Press Ctrl+C to stop")
    server))

(comment
  ;; Start the server
  (def server (-main))

  ;; Stop the server
  (http/stop server)
  )
