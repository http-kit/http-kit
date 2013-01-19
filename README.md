# HTTP Kit

[中文文档](https://github.com/shenfeng/http-kit/blob/master/README_CN.md)

Async HTTP Server and HTTP Client, write from scrach, for high performance, high concurrent Clojure application

## Features

Clean compact code: the jar size is ~80k

High performance, low resources usage, designed with server side use in mind

### HTTP Server

* High performance: thirdparty benchmark [clojure-web-server-benchmarks](https://github.com/ptaoussanis/clojure-web-server-benchmarks)
* Efficient support [long polling](http://en.wikipedia.org/wiki/Comet_(programming)
* Efficient Support [WebSocket](http://tools.ietf.org/html/rfc6455)
* Implement the ring adapter interface, just a drop in replacement to start
* Memory efficient: ~10K high traffic concurrent connections with ~128M heap

### HTTP Client

* Async with promise, async requests, single `@` to get the response synchronously
* Keep-alive
* Timeout per request

## Usage

### HTTP Server
```clj
[me.shenfeng/http-kit "2.0-SNAPSHOT"]

(:use me.shenfeng.http.server)          ; export run-server and defasync

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello word"})

(run-server app {:port 8080
                 :thread 4                     ; 4 http worker thread
                 :queue-size 20480             ; max job queued before reject to project self
                 :ip "127.0.0.1"               ; bind to localhost
                 :worker-name-prefix "worker-" ; thread name worker-1, worker-2, worker-3, ......
                 :max-line 4096                ; max http header line length
                 :max-body 1048576})           ; max http request body, 1M
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
; `http/get` `http/post` `http/head` `http/put` `http/delete`
(:require [me.shenfeng.http.client :as http])
```

```clj
;; Asynchronous，return a promise, response is ignored
(http/get "http://host.com/path")

;; Handle response asynchronously
(let [options {:timeout 200             ; ms
               :basic-auth ["user" "pass"]
               :query-params {:param "value"}
               :user-agent "User-Agent-string"
               :headers {"X-Header" "Value"}}]
  (http/get "http://host.com/path" options
            (fn [{:keys [status headers body error]}]
              (if error
                (println "Failed, exception is " error)
                (println "Async HTTP GET: " status)))))

;; Synchronous
(let [{:keys [status headers body error] :as resp} @(http/get "http://host.com/path")]
  (if error
    (println "Failed, exception: " error)
    (println "HTTP GET success: " status)))

;; Concurrent HTTP requests，handle responses in a sync style
(let [resp1 (http/get "http://host.com/path1")
      resp2 (http/get "http://host.com/path2")]
  (println "Response 1's status " {:status @resp1})
  (println "Response 2's status " {:status @resp2}))

;; Form params
(let [options {:form-parmas {:name "http-kit" :features ["async" "client" "server"]}}
      {:keys [status error]} @(http/post "http://host.com/path1" options)]
  (if error
    (println "Failed, exception is " error)
    (println "Async HTTP POST: " status)))

```

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

*sort by emacs `sort-line`*

* [Max Penet](https://github.com/mpenet)
* [Peter Taoussanis](https://github.com/ptaoussanis)


## Motivation

I write it for the HTTP server and HTTP client of [Rssminer](http://rssminer.net)

* Rssminer needs to be fast.
* Efficiently fetch feeds from Web.
* An asynchronous Server and Client is needed to proxy blogspot like sites for Rssminer's user

## Update history

* 1.0.3  using UTF8 to encode HTTP response header: fix can not encode Chinese char
* 1.1.0 defasync and async HTTP client clojure API
* 1.1.1 HTTP client: allow custom ACCEPT_ENCODING, default gzip, deflate
* 1.1.3 Better syntax for defasync
* 1.1.6 WebSocket support
* 1.2   Fix content-type for multipart/form-data


#### 2.0 (not released yet, comming soon)

HTTP Server:
  1. Support HTTP/1.0 keep-alive
  2. Better error reporting
  3. Better serving larget file(mmap),
  4. `:queue-size` option to protect high traffic web server
  5. API redisign: `async-response` and `if-ws-request` for better flexibility. Thanks [Peter Taoussanis](https://github.com/ptaoussanis)

HTTP Client:
  1. API redesign: by using promise, support both sync and async call. Thanks [Peter Taoussanis](https://github.com/ptaoussanis)
  2. Timeout per request
  3. Support keep-alive
