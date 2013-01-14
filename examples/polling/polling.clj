;;; run it: ./scripts/polling
(ns polling
  (:use me.shenfeng.http.server
        [ring.middleware.file-info :only [wrap-file-info]]
        [clojure.tools.logging :only [info]]
        [clojure.data.json :only [json-str]]
        (compojure [core :only [defroutes GET POST]]
                   [route :only [files not-found]]
                   [handler :only [site]]
                   [route :only [not-found]])))

(def ^{:const true} json-header {"Content-Type" "application/json; charset=utf-8"})

(defn- now [] (quot (System/currentTimeMillis) 1000))

(def clients (atom {}))                 ; a hub, a map of client => sequence number
(def current-max-id (atom 2))
(def all-msgs (atom [{:id 2,
                      :time (now)
                      :msg "this is a live chatroom, have fun",
                      :author "system"}]))

(defn- get-msgs [max-id]
  (filter #(> (-> %1 :id) max-id) @all-msgs))

(defn- send-pending-msgs [client]
  (client {:status 200
           :headers json-header
           :body (json-str (get-msgs (@clients client)))}))

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

(defn poll-mesg [req]
  (let [id (Integer/valueOf (-> req :params :id))
        msgs (get-msgs id)]
    (async-response respond!
                    (if (seq msgs)
                      (respond! {:status 200
                                 :headers json-header
                                 :body (json-str msgs)})
                      (swap! clients assoc respond! id)))))

(defn on-mesg-received [req]
  (let [{:keys [msg author]} (-> req :params)
        data {:msg msg
              :author author
              :time (now)
              :id (swap! current-max-id inc)}]
    (info "mesg received: " msg)
    (swap! all-msgs conj data)
    (doseq [client (keys @clients)]
      (send-pending-msgs client)
      (swap! clients dissoc client))
    {:status 200 :headers {}}))

(defroutes chartrootm
  (GET "/poll" [] poll-mesg)
  (POST "/msg" [] on-mesg-received)
  (files "" {:root "examples/polling"})
  (not-found "<p>Page not found.</p>" ))

(defonce server (atom nil))

(defn -main [& args]
  (when-not (nil? @server)
    (@server)
    (reset! server nil))
  (reset! server (run-server (-> chartrootm site wrap-request-logging)
                             {:port 9898 :thread 6}))
  (info "server started. http://127.0.0.1:9898"))
