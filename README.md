# Http kit

[中文文档](https://github.com/shenfeng/http-kit/blob/master/README_CN.md)

* A high performance HTTP Server(Ring adapter) with async and websocket for Clojure web app.
* A high performance async HTTP Client.

## Features

* Clean compact code: the jar size is ~80k

### HTTP Server

* High performance: [clojure-web-server-benchmarks](https://github.com/ptaoussanis/clojure-web-server-benchmarks)
* Efficient support [long polling](http://en.wikipedia.org/wiki/Comet_(programming)
* Efficient Support [WebSocket](http://tools.ietf.org/html/rfc6455)
* Implement the ring adapter interface, just a drop in replacement to start
* Memory efficient. Less than 1M of RAM for server, few kilobytes of RAM per connection

For Efficience => just a few k of memory to maintain a connection

### HTTP Client

* High performance, designed with server use in mind
* Sync & async, virtually the same API.
* Keep-alive, Timeout per request


## Usage

### HTTP Server
```clj
[me.shenfeng/http-kit "1.3-SNAPSHOT"]

(:use me.shenfeng.http.server)          ; export run-server and defasync

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello word"})

(run-server app {:port 8080
                 :thread 4              ; 4 http worker thread
                 :ip "127.0.0.1"        ; bind to localhost
                 :worker-name-prefix "worker-" ; thread name worker-1, worker-2, worker-3, ......
                 :max-line 2048         ; max http header line length
                 :max-body 20480        ; max http request body, 20k
                 })

```

### Websocket
```clj
(:use me.shenfeng.http.server)

(defn chat-handler [req]
  (if-ws-request con
                 (on-mesg con (fn [msg]
                                ;; echo back
                                (send-mesg con msg)))))

(run-server chat-handler {:port 8080})

```

These is a live chartroom as an example:
[examples/websocket](https://github.com/shenfeng/http-kit/tree/master/examples/websocket)

run it:

```sh
./scripts/websocket # try open two browser tab, view it on http://127.0.0.1:9899/
```

### Asynchronous (Long polling)
```clj
(:use me.shenfeng.http.server)

(defn async-handler [req]
  (async-response respond!
                  (future (respond! "hello world async"))))

(run-server async-handler {:port 8080})
```

These is a live chartroom as an example:
[examples/polling](https://github.com/shenfeng/http-kit/tree/master/examples/polling)

run it:

```sh
./scripts/polling # try open two browser tab, view it on http://127.0.0.1:9898/
```

### HTTP Client

```clj
(:require [me.shenfeng.http.client :as http])
```

#### HTTP GET

```clj
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

```

#### HTTP POST

```clj
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

```

### HTTP HEAD, OPTIONS, DELETE, PUT

Just like `get` and `post`

## Benchmark

[clojure-web-server-benchmarks](https://github.com/ptaoussanis/clojure-web-server-benchmarks)

**httperf and ab has some issues in OS X, On Linux runs fine**

The Server runs quite fast: It handles tens of thousands requests per seconds on moderate PC:

There are scripts to do some benchmark. I use it to get some ideas
about how fast it can send and receive bytes.

#### ab version:
```sh
git clone git://github.com/shenfeng/http-kit.git && cd http-kit && rake bench
```
It compare with
[ring-jetty-adapter](https://github.com/mmcgrana/ring)
[async-ring-adapter](https://github.com/shenfeng/async-ring-adapter)

#### [httperf version](https://github.com/shenfeng/http-kit/tree/master/scripts/httperf):

```sh
git clone git://github.com/shenfeng/http-kit.git && cd http-kit && ./scripts/httperf
```

## Contributors

[Peter Taoussanis](https://github.com/ptaoussanis)

## Motivation

I write it for the HTTP server and HTTP client of [Rssminer](http://rssminer.net)

* Rssminer need to be fast.
* Efficiently fetch feeds from Web.
* I want to learn how to write a HTTP Server from scratch
* I need an asynchronous Server and Client to proxy blogspot like sites for Rssminer's user [a feature implemented but deleted later]

## Update history

* 1.0.3  using UTF8 to encode HTTP response header: fix can not encode Chinese char
* 1.1.0 defasync and async HTTP client clojure API
* 1.1.1 HTTP client: allow custom ACCEPT_ENCODING, default gzip, deflate
* 1.1.3 Better syntax for defasync
* 1.1.6 WebSocket support
* 1.2   Fix content-type for multipart/form-data


#### 2.0 (not released yet)

HTTP Server:
  1. Support HTTP/1.0 keep-alive
  2. Better error reporting
  3. Better serving larget file(mmap),
  4. :queue-size option to protect overloaded server
  5. API redisign: `async-response` and `if-ws-request` for better flexibility. Thanks [Peter Taoussanis](https://github.com/ptaoussanis)

HTTP Client:
  1. API redesign: by using promise, support both sync and async call. Thanks [Peter Taoussanis](https://github.com/ptaoussanis)
  2. Timeout per request
  3. Support keep-alive
