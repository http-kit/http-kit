# Http Kit

高性能HTTP Server (Ring adapter)， HTTP Client。使你的Clojure Web 程序拥有类似Nginx的性能

## 特点

* 从头写，jar文件仅80k => 代码少，bug少
* 为高性能服务器打造

### HTTP Server

* **性能** 第三方独立测评结果: [clojure-web-server-benchmarks](https://github.com/ptaoussanis/clojure-web-server-benchmarks)
* 并发支持：仅需几K内存来保持一个HTTP连接，空闲连接几乎不影响latency。
* 支持Async [HTTP长连](http://en.wikipedia.org/wiki/Comet_(programming)，实时push 更新给客户端
* 支持 [WebSocket](http://tools.ietf.org/html/rfc6455)，实时双向通讯

### HTTP Client

* 同步的感觉，异步的API(promise)，需要结果，加@就行
* 每个请求可独立设置超时： *服务器程序，超时很重要*
* keep-alive： *如果对方服务器支持。为性能，不遗余力*
* 线程安全

http-kit使用了和Nginx相似的并发模型，具有和Nginx相似的并发处理能力。

## HTTP Server 用法

### 添加依赖

```clj
;; 在project.clj中添加依赖
[me.shenfeng/http-kit "2.0-SNAPSHOT"]
[compojure "1.1.1"]

;;; 引入依赖
(:use me.shenfeng.http.server           ;; 引入 http-kit Server
      compojure.core)                   ;; Router， 方便定义handler
```

### Hello world

```clj
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
```

### 异步接口，http长连，long polling

一个接口：`async-response`

**示例**

```clj
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

```
用HTTP长连实现的聊天室例子，在[examples/polling目录](https://github.com/shenfeng/http-kit/tree/master/examples/polling)


### Websocket，实时双向通信

* `if-ws-request` 或者 `when-ws-request`: 判断是否是Websocket请求，并开始处理
* `on-mesg`: 收到来自客户端发来的文本消息
* `send-mesg`: 主动给客户端发送文本消息
* `on-close`:  连接关闭（服务器主动，或者客户端主动）
* `close-conn`:  服务器主动关闭连接

**示例**

```clj
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
```
用WebSocket实现的聊天室， 在[examples/websocket目录](https://github.com/shenfeng/http-kit/tree/master/examples/websocket)

## HTTP Client 用法

```clj
(:require [me.shenfeng.http.client :as http])
; 包括 `http/get`, `http/post`, `http/put` `http/delete` `http/put`
```

```clj

;; 异步调用，返回的promise，忽略结果
(http/get "http://host.com/path")

;; 异步调用，异步处理
(let [options {:timeout 200             ; ms
               :basic-auth ["user" "pass"]
               :user-agent "User-Agent-string"
               :headers {"X-Header" "Value"}}]
  (http/get "http://host.com/path" options {:keys [status headers body] :as resp}
            (if status
              (println "Async HTTP GET: " status)
              (println "Failed, exception is " resp))))

;; 同步调用
(let [{:keys [status headers body] :as resp} @(http/get "http://host.com/path")]
  (if status
    (println "HTTP GET success: " status)
    (println "Failed, exception: " resp)))

;; 并发调用，同步处理
(let [resp1 (http/get "http://host.com/path1")
      resp2 (http/get "http://host.com/path2")]
  (println "Response 1's status " {:status @resp1})
  (println "Response 2's status " {:status @resp2}))

;; Form提交
(let [form-parms {:name "http-kit" :features ["async" "client" "server"]}
      {:keys [status headers body] :as resp} (http/post "http://host.com/path1"
                                                        {:form-parmas form-parms})]
  (if status
    (println "Async HTTP POST: " status)
    (println "Failed, exception is " resp)))

```
