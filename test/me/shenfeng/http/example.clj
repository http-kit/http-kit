(ns me.shenfeng.http.example
  (:use me.shenfeng.http.server
        [ring.middleware.file-info :only [wrap-file-info]]
        [clojure.tools.logging :only [info]]
        [clojure.data.json :only [json-str]]
        (compojure [core :only [defroutes GET POST]]
                   [route :only [files not-found]]
                   [handler :only [site]]
                   [route :only [not-found]])))

(def id (atom 2))

(def clients (atom {}))                 ; {cb: sequence number}

(defn- now [] (quot (System/currentTimeMillis) 1000))

;;; all msg data
(def datas (atom [{:id 2, :time (now)
                   :msg "this is a live chatroom, have fun",
                   :author "system"}]))

(defn- send-client [cb]
  (cb {:status 200
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body (json-str (filter #(> (-> %1 :id)
                                   (@clients cb))
                               @datas))}))

(defn- wrap-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [start (System/currentTimeMillis)
          resp (handler req)
          finish (System/currentTimeMillis)]
      (info (name request-method) (:status resp)
            (if-let [qs (:query-string req)]
              (str uri "?" qs) uri)
            (str (- finish start) "ms"))
      resp)))

(defn- on-message-received [msg]
  (let [data (assoc msg
               :time (now)
               :id (swap! id inc))]
    (swap! datas conj data)
    (doseq [cb (keys @clients)]
      (send-client cb)
      (swap! clients dissoc cb))
    data))

(defasync polling-message [req] cb
  (let [id (Integer/valueOf (-> req :params :id))]
    (let [msgs (filter #(> (-> %1 :id) id)
                       @datas)]
      (if (seq msgs)
        (cb {:status 200
             :headers {"Content-Type"
                       "application/json; charset=utf-8"}
             :body (json-str msgs)})
        (swap! clients assoc cb id)))))

(defn send-message [req]
  (let [{:keys [msg author]} (-> req :params)]
    {:status 200
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body (json-str [(on-message-received {:msg msg
                                            :author author})])}))

(defroutes chartrootm
  (GET "/poll" [] polling-message)
  (POST "/msg" [] send-message)
  (files "")
  (not-found "<p>Page not found.</p>" ))

(defonce server (atom nil))

(defn -main [& args]
  (when-not (nil? @server)
    (@server)
    (reset! server nil))
  (reset! server (run-server (-> chartrootm site wrap-logging)
                             {:port 9898
                              :thread 2})))
