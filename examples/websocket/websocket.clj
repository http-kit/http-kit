;;; run it ./scripts/websocket
(ns websocket
  (:use org.httpkit.server
        [ring.middleware.file-info :only [wrap-file-info]]
        [clojure.tools.logging :only [info]]
        [clojure.data.json :only [json-str read-json]]
        (compojure [core :only [defroutes GET POST]]
                   [route :only [files not-found]]
                   [handler :only [site]]
                   [route :only [not-found]])))

(defn- now [] (quot (System/currentTimeMillis) 1000))

(def clients (atom {}))                 ; a hub, a map of client => sequence number
(def current-max-id (atom 2))
(def all-msgs (atom [{:id 2,
                      :time (now)
                      :msg "this is a live chatroom, have fun",
                      :author "system"}]))

(defn- wrap-request-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [start (System/currentTimeMillis)
          resp (handler req)
          finish (System/currentTimeMillis)]
      (info (name request-method) (:status resp)
            (if-let [qs (:query-string req)]
              (str uri "?" qs) uri)
            (str (- finish start) "ms"))
      resp)))

(defn- get-msgs [max-id]
  (filter #(> (-> %1 :id) max-id) @all-msgs))

(defn on-mesg-received [data]
  (info "mesg received" data)
  (if (:msg data)
    (let [data (merge data {:time (now) :id (swap! current-max-id inc)})]
      (swap! all-msgs conj data)))
  (doseq [client (keys @clients)]
    (send-mesg client (json-str (get-msgs (@clients client))))))

(defn chat-handler [req]
  (when-ws-request req con
                   (info con "connected")
                   (swap! clients assoc con 1)
                   ;; (write con (json-str (get-msgs 1)))
                   (on-mesg con (fn [msg]
                                  (on-mesg-received (read-json msg))))
                   (on-close con (fn [status]
                                   (info con "closed, status" status)))))

(defwshandler echo-handler [req] con
  (on-mesg con (fn [msg]
                 (send-mesg con msg))))

(defroutes chartrootm
  (GET "/ws" []  chat-handler)
  (GET "/test" []  echo-handler)
  (files "" {:root "examples/websocket"})
  (not-found "<p>Page not found.</p>" ))

(defonce server (atom nil))

(defn -main [& args]
  (when-not (nil? @server)
    (@server)
    (reset! server nil))
  (reset! server (run-server (-> chartrootm site wrap-request-logging)
                             {:port 9899 :thread 6}))
  (info "server started. http://127.0.0.1:9899"))
