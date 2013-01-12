;; 在project.clj中添加依赖
[me.shenfeng/http-kit "1.2"]
[compojure "1.1.1"]

;;; 引入依赖
(:use me.shenfeng.http.server           ;; 引入 http-kit Server
      compojure.core)                   ;; Router， 方便定义handler


;; 普通Clojure函数：handler。 参数request：map，包含HTTP Request的信息， 如form参数，上传的文件，URL等。
;; 打印 (println request), 以查看
(defn web-handler [request]
  ;; 返回符合ring规范的response map
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf8"}
   ;; body 可以是字符串，文件，InputStream，或者Sequence
   :body "hello word from http-kit"})

;; 启动http server，监听在8080端口。 run-server返回一个函数，用于停止server
;; 浏览器访问 http://127.0.0.1:8080/，将看到 hello word from http-kit
(run-server handler {:port 8080})



;; 普通Clojure函数。 参数request：map，包含HTTP Request的信息
(defn async-handler [request]
  ;; 通过async-response宏实现HTTP长连
  (async-response respond! ;; respond!是一个函数，可以被保存下来，在任意时刻，在任意线程调用，用于返回结果给浏览器
                  (future
                    ;; 可以返回字符串，文件，InputStream，或者Sequence。并且可以加上HTTP status， headers等
                    (respond! "异步调用"))))

(defroutes web-handler ;;定义router： URL dispatch 规则
  (GET "/" [] "This is Index Page")
  (GET "/async" [] async-handler))

(run-server web-handler {:port 8080})

;; 普通Clojure函数。
(defn ws-handler [request]
  ;; 通过if-ws-request 支持websocket， 也可以用when-ws-request
  (if-ws-request request
                 conn ;; conn表示websocket连接，可以被保存下来，它是线程安全的
                 (do
                   ;; websocket连接建立成功
                   (on-mesg conn (fn [mesg] ;; 接收到来自客户端发来的文本消息 mesg
                                   ))
                   (on-close conn (fn [] ;; websocket连接已经关闭（客户端关闭，服务端关闭）
                                    ;; 可用于善后处理
                                    ))
                   ;; 服务器端向客户端发送文本消息， 可在任意线程调用
                   (send-mesg conn "A message from server by websocket")
                   (close-conn conn)) ;; 服务器端主动关闭连接
                 ;; 不是websocket请求
                 "No a websocket request?"))

(defroutes web-handler
  (GET "/" [] "This is Index Page")
  (GET "/ws" [] ws-handler))

(run-server web-handler {:port 8080})

(:require [me.shenfeng.http.client :as http])

;; 异步调用，忽略返回的promise
(http/get "http://host.com/path")

;; 异步调用，异步处理
(http/get "http://host.com/path" {:keys [status headers body] :as resp}
          (if status
            (println "Async HTTP Get: " status)
            (println "Failed, exception is " resp)))

;; 同步调用
(let [{:keys [status headers body] :as resp} @(http/get "http://host.com/path")]
  (if status
    (println "HTTP Get success: " status)
    (println "Failed, exception: " resp)))

;; 异步调用，Timeout 200ms， Basic Auth user@pass， 指定User-Agent
(let [options {:timeout 200
               :basic-auth ["user" "pass"]
               :headers {"User-Agent" "User-Agent-string"}}]
  (http/get "http://host.com/path" options {:keys [status headers body] :as resp}
            (if status
              (println "Async HTTP Get: " status)
              (println "Failed, exception: " resp))))

(def post-options {:form-params {:params1 "value" :params2 ["v1" "v2"]}
                   :timeout 200 ;; timeout 200ms
                   :headers {"Key" "Value"}})

;;; 异步调用，忽略返回的promise
(http/post "http://host.com/path" post-options)

;;; 异步调用，异步处理
(http/post "http://host.com/path" post-options {:keys [status headers body] :as resp}
           (if status
             (println "Async HTTP Post: " status)
             (println "Failed, exception: " resp)))

;;; 同步调用
(let [{:keys [status headers body] :as resp} @(http/post "http://host.com/path")]
  (if status
    (println "Sync HTTP Post: " status)
    (println "Failed, exception: " resp)))


;;; ----------------------------------------


;; Asynchronous，return a promise
(http/get "http://host.com/path")

;; Asynchronous
(http/get "http://host.com/path" {:keys [status headers body] :as resp}
          (if status
            (println "Async HTTP Get: " status)
            (println "Failed, exception is " resp)))

;; Synchronous
(let [{:keys [status headers body] :as resp} @(http/get "http://host.com/path")]
  (if status
    (println "HTTP Get success: " status)
    (println "Failed, exception: " resp)))

;; Asynchronous: Timeout 200ms, Basic Auth user@pass, Customise User-Agent
(let [options {:timeout 200
               :basic-auth ["user" "pass"]
               :headers {"User-Agent" "User-Agent-string"}}]
  (http/get "http://host.com/path" options {:keys [status headers body] :as resp}
            (if status
              (println "Async HTTP Get: " status)
              (println "Failed, exception: " resp))))

(def post-options {:form-params {:params1 "value" :params2 ["v1" "v2"]}
                   :timeout 200 ;; timeout 200ms
                   :headers {"Key" "Value"}})

;; Asynchronous，return a promise
(http/post "http://host.com/path" post-options)

;; Asynchronous
(http/post "http://host.com/path" post-options {:keys [status headers body] :as resp}
           (if status ;; when response is ready
             (println "Async HTTP Post: " status)
             (println "Failed, exception: " resp)))

;;; Synchronous
(let [{:keys [status headers body] :as resp} @(http/post "http://host.com/path")]
  (if status
    (println "Sync HTTP Post: " status)
    (println "Failed, exception: " resp)))
