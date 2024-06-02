# Getting started

```clj
(ns my-ns
  (:require [org.httpkit.server :as hk-server]))
```

http-kit server uses an **event-driven, non-blocking I/O model** that makes it lightweight and scalable.

It's written to conform to the standard Clojure web server [Ring spec](https://github.com/ring-clojure/ring), with asynchronous and WebSocket support. http-kit is an (almost) drop-in replacement for [ring-jetty-adapter](https://clojars.org/ring/ring-jetty-adapter).

## Start server

`run-server` starts a Ring-compatible HTTP server.

```clj
(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello HTTP!"})

(def my-server (hk-server/run-server app {:port 8080})) ; Start server
```

See [API docs](http://http-kit.github.io/http-kit/org.httpkit.server.html#var-run-server) for detailed info on server options

## Stop server

The `run-server` call above returns a stop function that you can call like so:

```clj
(my-server) ; Immediate shutdown (breaks existing reqs)

;; or

;; Graceful shutdown (wait <=100 msecs for existing reqs to complete):
(my-server :timeout 100)
```

## Websockets

There are 2 ways to handle WebSockets with `http-kit`:
- use `http-kit`'s own unified API for WebSocket and HTTP long-polling/streaming
- use the standard Ring WebSocket API

### Approach 1

```clj
(ns demo
  (:require [org.httpkit.server :as hk-server]))

(def channels (atom #{}))

(defn on-open [ch]
  (swap! channels conj ch))

(defn on-receive [ch message]
  (doseq [ch @channels]
    (hk-server/send! ch (str "broadcasting: " message))))

(defn on-close [ch status-code]
  (swap! channels disj ch))

(defn app [req]
  (if-not (:websocket? req)
    {:status 200 :headers {"content-type" "text/html"} :body "<h1>Main screen turn on.</h1><h2>Start connecting websockets.</h2>"}
    (hk-server/as-channel req
                          {:on-open    #(on-open    %)
                           :on-receive #(on-receive %1 %2)
                           :on-close   #(on-close   %1 %2)})))

(def server (hk-server/run-server app {:port 8080}))
```

## Production environments

http-kit runs alone happily, which is handy for development and quick deployment. 

But for production environments, it's **strongly recommended** to run http-kit behind a battle-hardened reverse proxy like [nginx](https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/), [Caddy](https://caddyserver.com/docs/quick-starts/reverse-proxy), [HAProxy](https://www.haproxy.org/), etc.

http-kit's philosophy is to **focus on running Clojure code**, while leaving as much of the rest as possible to these very heavily optimised+tested solutions for things like **HTTPS**, compression, load balancing, static file serving, etc.

###  Sample Nginx configuration

```
upstream my-pool {
	ip_hash;
	server localhost:8080;
	# Can add extra servers here
	keepalive 32;
}

server {
    location /static/ {  # static content
        alias   /var/www/public/;
    }
    location / {
    	proxy_http_version                 1.1;
    	proxy_pass                         http://my-pool;
    	proxy_pass_request_headers         on;
    	proxy_set_header Host              $host;
    	proxy_set_header X-Real-IP         $remote_addr;
    	proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    	proxy_set_header X-Forwarded-Proto $scheme;
    	proxy_set_header Upgrade           $http_upgrade;       # For WebSockets
    	proxy_set_header Connection        $connection_upgrade; # For WebSockets
        access_log  /var/log/nginx/access.log;
    }
}
```

# Advanced topics

## Custom request queues

http-kit server's worker pool can be easily customised for arbitrary control and monitoring, e.g.:

```clojure
(require '[org.httpkit.server :as hk-server])

(defn new-http-kit-pool
  [& {:keys [queue-capacity min-thread-count max-thread-count]
      :or   {queue-capacity 20480
             min-thread-count 4
             max-thread-count 8}}]

  (let [ptf   (org.httpkit.PrefixThreadFactory. "worker-")
        queue (java.util.concurrent.ArrayBlockingQueue. queue-capacity)
        pool  (java.util.concurrent.ThreadPoolExecutor.
                (long min-thread-count) (long max-thread-count)
                0 java.util.concurrent.TimeUnit/MILLISECONDS
                queue ptf)]

    {:queue queue
     :pool  pool}))

(defonce my-http-kit-pool (new-http-kit-pool))

(hk-server/run-server
  (fn my-handler [ring-req] {:body "hello"})
  {:worker-pool (:pool my-http-kit-pool)
   :port 11000})

(.size (:queue my-http-kit-pool)) ; Get current queue size
```

### Java 19+ virtual threads

As above, but using [`java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor`](https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor()):

```clojure
(require '[org.httpkit.server :as hk-server])

(hk-server/run-server
  (fn my-handler [ring-req] {:body "Hello"})
  {:worker-pool (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
   :port 11000})
```

## Unix Domain Sockets (UDS)

http-kit 2.7+ supports server+client UDS on Java 16+.  
To use, plug in appropriate `java.net.SocketAddress` and `java.nio.channels.SocketChannel` constructor fns:

```clojure
(require '[org.httpkit.server :as hk-server])

(let [my-uds-path "/tmp/test.sock"
      my-server
      (hk-server/run-server my-routes
        {:address-finder  (fn []         (UnixDomainSocketAddress/of my-uds-path))
         :channel-factory (fn [_address] (ServerSocketChannel/open StandardProtocolFamily/UNIX))})]
  <...>
  )
```

See [`run-server`](http://http-kit.github.io/http-kit/org.httpkit.server.html#var-run-server) for more info.
