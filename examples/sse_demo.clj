(ns sse-demo
  "Simple SSE demo that broadcasts to one client at a time"
  (:require
   [org.httpkit.server :refer [run-server as-channel send! open? close]]
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
       (let [event-id (atom 0)
             cancelled? (atom false)]

         ;; Cleanup function to stop broadcasting
         (add-watch cancelled? :cleanup
           (fn [_ _ _ is-cancelled]
             (when is-cancelled
               (println "Stopped broadcasting for channel" ch))))

         ;; Recursive function to send events
         ((fn broadcast []
            (when (and (open? ch) (not @cancelled?))
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

(defn html-page
  "Simple HTML page with SSE client"
  [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   "<!DOCTYPE html>
<html>
<head>
    <title>SSE Demo</title>
    <style>
        body { font-family: monospace; padding: 20px; }
        #events { border: 1px solid #ccc; padding: 10px; height: 400px; overflow-y: auto; }
        #status { margin: 10px 0; padding: 10px; border-radius: 4px; }
        .connected { background-color: #d4edda; color: #155724; }
        .disconnected { background-color: #f8d7da; color: #721c24; }
        button { padding: 10px 20px; margin: 5px; cursor: pointer; }
    </style>
</head>
<body>
    <h1>Server-Sent Events Demo</h1>
    <div id=\"status\" class=\"disconnected\">Disconnected</div>
    <button onclick=\"connect()\">Connect</button>
    <button onclick=\"disconnect()\">Disconnect</button>
    <button onclick=\"clearEvents()\">Clear Events</button>
    <div id=\"events\"></div>

    <script>
        let eventSource = null;
        const eventsDiv = document.getElementById('events');
        const statusDiv = document.getElementById('status');

        function addEvent(message) {
            const div = document.createElement('div');
            div.textContent = new Date().toLocaleTimeString() + ' - ' + message;
            eventsDiv.appendChild(div);
            eventsDiv.scrollTop = eventsDiv.scrollHeight;
        }

        function updateStatus(connected) {
            if (connected) {
                statusDiv.textContent = 'Connected';
                statusDiv.className = 'connected';
            } else {
                statusDiv.textContent = 'Disconnected';
                statusDiv.className = 'disconnected';
            }
        }

        function connect() {
            if (eventSource) {
                addEvent('Already connected');
                return;
            }

            eventSource = new EventSource('/sse');

            eventSource.onopen = function() {
                addEvent('Connected to server');
                updateStatus(true);
            };

            eventSource.onmessage = function(event) {
                addEvent('Received: ' + event.data);
            };

            eventSource.onerror = function(error) {
                addEvent('Error occurred, connection lost');
                updateStatus(false);
                eventSource.close();
                eventSource = null;
            };
        }

        function disconnect() {
            if (eventSource) {
                eventSource.close();
                eventSource = null;
                addEvent('Disconnected from server');
                updateStatus(false);
            }
        }

        function clearEvents() {
            eventsDiv.innerHTML = '';
        }

        // Auto-connect on page load
        connect();
    </script>
</body>
</html>"})

(defn router [request]
  (case (:uri request)
    "/sse" (sse-handler request)
    "/" (html-page request)
    {:status 404 :body "Not found"}))

(defn -main [& args]
  (let [port 8080]
    (run-server router {:port port})
    (println (format "SSE demo server running on http://localhost:%d" port))
    (println "Press Ctrl+C to stop")))

(comment
  ;; Start the server
  (def server (-main))

  ;; Stop the server
  (server)
  )
