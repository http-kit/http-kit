# Http kit

* An event driven ring adapter for Clojure web app.
* An event driven HTTP client.

The ring adapter will follow [ring SPEC]
(https://github.com/mmcgrana/ring/blob/master/SPEC).
There's
[Unit test](https://github.com/shenfeng/http-kit/blob/master/test/me/shenfeng/http/server/server_test.clj)
to make sure it.

I also add an
[async extension](https://github.com/shenfeng/http-kit/blob/master/src/java/me/shenfeng/http/server/IListenableFuture.java)
to the ring SPEC. The unit test has sample usage.

## Why

I write it for the HTTP server and HTTP client of
[Rssminer](http://rssminer.net)

* Efficiently fetch feeds from Web.
* Fast proxy some sites for Chinese user.
* Rssminer need to be fast.

## Goal
* Clean compact code.
* Non-blocking IO
* Memory efficient. Memory is cheap, but anyway, I will do my best to
  save it.
* Support Socks proxy. `SSH -D` create a Socks server, in china, proxy
  is a must.

## Usage

### HTTP Server
```clj
[me.shenfeng/http-kit "1.0.2"]

(:use me.shenfeng.http.server)          ; export run-server

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello word"})

(run-server app {:port 8080
                 :thread 4              ; 4 http worker thread
                 :ip "127.0.0.1"        ; bind to localhost
                 :max-body 20480        ; max http request body, 20k
                 })

```

#### Async extension example
```clj
(:import me.shenfeng.http.server.IListenableFuture)

;;; efficient hold on request, return when thing is ready
;;; event driven
(def async-body
  (reify IListenableFuture
    (addListener [this cb]
      (.start (Thread. (fn []
                         (println "sleep 100ms")
                         (Thread/sleep 100)
                         ;; inform thing is ready, adpter will
                         ;; .get to get the real response
                         (.run cb)))))
    (get [this]
      ;;this is the real ring response, {status, headers, body}
      {:status 204
       :headers {"Content-type" "application/json"}})))

(defn app [req]
  ;; any number will do, it's ignored.
  ;; just transparent pass all ring middleware
  {:status  200
   :body    async-body})
```

### HTTP Client

```java
int socketTimeout = 60000; // 60s
String userAgent = "bot1";
HttpClient client = new HttpClient(new HttpClientConfig(socketTimeout, userAgent));

URI uri = new URI("http://shenfeng.me");
Map<String, String> headers = new HashMap<String, String>();
headers.put("Cache-Control", "no-cache");
// ... more header

client.get(uri, headers, new IRespListener() { // Non-blocking. DNS lookup is in current thread
    public void onThrowable(Throwable t) {
        // IOException, Timeout
    }

    public int onInitialLineReceived(HttpVersion version,
            HttpStatus status) {
        // CONTINUE or ABORT
        return 0;
    }

    public int onHeadersReceived(Map<String, String> headers) {
        // CONTINUE or ABORT
        return 0;
    }

    public void onCompleted() {
        // All bytes are downloaded
    }

    public int onBodyReceived(byte[] buf, int length) {
        // bytes received from remote server
        // CONTINUE or ABORT
        return 0;
    }
});
```

## Limitation

### HTTP client
* HTTP proxy is not supported

### HTTP server
* Client request is buffered in memory (can't handle very large
  file upload)
* No timeout handling. The server is intended to be protected by
  others (like Nginx)

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
