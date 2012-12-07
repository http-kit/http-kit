# Http kit

* An event driven ring adapter for Clojure web app.
* An event driven HTTP client.

The ring adapter follows [ring SPEC]
(https://github.com/mmcgrana/ring/blob/master/SPEC).
There's
[Unit test](https://github.com/shenfeng/http-kit/blob/master/test/me/shenfeng/http/server/server_test.clj)
to make sure it.

I also add an
[async extension](https://github.com/shenfeng/http-kit/blob/master/src/java/me/shenfeng/http/server/IListenableFuture.java)
to the ring SPEC, for efficient long polling. The unit test has sample usage.

## Motivation

I write it for the HTTP server and HTTP client of [Rssminer](http://rssminer.net)

* Rssminer need to be fast.
* Efficiently fetch feeds from Web.
* I want to learn how to write a HTTP Server from scratch
* I need an asynchronous Server and Client to proxy blogspot like sites for Rssminer's user [a feature implemented but deleted later]

## Features
* Simple and works
* Clean compact code
* Efficient support long polling
* Efficient Support WebSocket
* Implement the ring adapter interface, just a drop in replacement to start
* Memory efficient. Memory is cheap, but anyway, I do my best to save it.
* Support Socks proxy. `SSH -D` create a Socks server, in china, proxy is a must.

for Efficient => just a few k of memory to maintain a connection

## Usage

### HTTP Server
```clj
[me.shenfeng/http-kit "1.2"]

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

#### Websocket
```clj
(:use me.shenfeng.http.server)

(defwshandler chat-handler [req] con
  (on-mesg con (fn [msg]
                 ;; echo back
                 (send-mesg con msg))))

(run-server chat-handler {:port 8080})

```

These is a live chartroom as an example:
[websocket.clj](https://github.com/shenfeng/http-kit/blob/master/test/me/shenfeng/http/websocket.clj)

run it:

```sh
./scripts/websocket # try open two browser tab, view it on http://127.0.0.1:9899/
```

#### Async extension
```clj
(:use me.shenfeng.http.server)

(defasync async [req] cb
  (.start (Thread. (fn []
                     (Thread/sleep 1000)
                     ;; return a ring spec response
                     ;; call (cb req) when response ready
                     (cb {:status 200 :body "hello async"})))))

(run-server async {:port 8080})
```
These is a live chartroom as an example:
[polling.clj](https://github.com/shenfeng/http-kit/blob/master/test/me/shenfeng/http/polling.clj)

run it:

```sh
./scripts/polling # try open two browser tab, view it on http://127.0.0.1:9898/
```

### HTTP Client

```clj

(defn on-response [resp]
  ;; {:status 200 :body "....." :headers {:key val :key val}}
  (println resp))

;;; initialize, timeout is 40s, and default user-agent
(http/init :timeout 40000 :user-agent "http-kit/1.1")

;;; other params :headers :proxy binary? keyify?
(http/get {:url "http://shenfeng.me" :cb on-response})

;;; other params :headers :proxy binary? keyify?
(http/post {:url "http://example/"
            :cb on-response
            :body {"name" "http-kit" "author" "shenfeng"}  :binary? true})

```

## Limitation

### HTTP client
* HTTP proxy is not supported

### HTTP server
* Client request is buffered in memory (very large file upload)
* No timeout handling. The server is intended to be protected by others (like Nginx)

# Benchmark

There is a script to do some simple benchmark. I use it to get some ideas
about how fast it can send and receive bytes.

#### Run it yourself
```sh
git clone git://github.com/shenfeng/http-kit.git && cd http-kit && rake bench
```
It compare with
[ring-jetty-adapter](https://github.com/mmcgrana/ring)
[async-ring-adapter](https://github.com/shenfeng/async-ring-adapter)

### Update history

* 1.0.3  using UTF8 to encode HTTP response header: fix can not encode
  Chinese char

* 1.1.0 defasync and async HTTP client clojure API
* 1.1.1 HTTP client: allow custom ACCEPT_ENCODING, default gzip, deflate
* 1.1.3 Better syntax for defasync
* 1.1.6 WebSocket support
* 1.2   fix content-type for multipart/form-data
