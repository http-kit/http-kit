# HTTP Kit

### A high-performance event-driven HTTP client+server for Clojure

[CHANGELOG][] | Current [semantic](http://semver.org/) version/s:

```clojure
[http-kit "2.3.0"]         ; Stable, published by contributors, see CHANGELOG for details
[http-kit "2.4.0-alpha3"]  ; Dev,    published by contributors, see CHANGELOG for details
[http-kit "2.1.19"]        ; Legacy, published by @shenfeng
```

See [http-kit.org](http://http-kit.org) for documentation, examples, benchmarks, etc. (no longer maintained, some examples may contain minor bugs).

## Project status

http-kit's author ([@shenfeng][]) unfortunately hasn't had much time to maintain http-kit recently. To help out I'll be doing basic issue triage, accepting minor/obvious PRs, etc.

A big thank you to the **[current contributors](https://github.com/http-kit/http-kit/graphs/contributors)** for keeping the project going! **Additional contributors welcome**: please ping me if you'd be interested in lending a hand.

\- [@ptaoussanis][]

### Hack locally

Hacker friendly: zero dependencies, written from the ground-up with only ~3.5k lines of code (including java), clean and tidy.

```sh
# Modify as you want, unit tests back you up:
lein test

# May be useful (more info), see `server_test.clj`:
./scripts/start_test_server

# Some numbers on how fast can http-kit's client can run:
lein test :benchmark
```

### Contact & Contribution

Please use the [GitHub issues page](https://github.com/http-kit/http-kit/issues) for feature suggestions, bug reports, or general discussions. Current contributors are listed [here](https://github.com/http-kit/http-kit/graphs/contributors). The http-kit.org website is also on GitHub [here](https://github.com/http-kit/http-kit.github.com).

### License

Copyright &copy; 2012-2018 [Feng Shen](http://shenfeng.me/). Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

[CHANGELOG]: https://github.com/http-kit/http-kit/releases
[@shenfeng]: https://github.com/shenfeng
[@ptaoussanis]: https://github.com/ptaoussanis

# Usage

## Ring adapter(HTTP server) with async and websocket extension

`(require '[org.httpkit.server :as server]) ; Make the org.httpkit.server namespace accesible via server`

The server uses an event-driven, non-blocking I/O model that makes it lightweight and scalable. It's written to conform to the standard Clojure web server [Ring spec](https://github.com/ring-clojure/ring), with asynchronous and websocket extension. HTTP Kit is ([almost](http://www.http-kit.org/migration.html)) drop-in replacement of ring-jetty-adapter
Hello, Clojure HTTP server

### Hello, Clojure HTTP server

`run-server` starts a Ring-compatible HTTP server. You may want to do [routing with compojure](http://www.http-kit.org/server.html#routing)

`(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello HTTP!"})
(server/run-server app {:port 8080})`

Options:

   * `:ip:` which IP to bind, default `to 0.0.0.0`
   * `:port:` which port listens for incoming requests, default to 8090
   * `:thread:` How many threads to compute response from request, default to 4
   * `:worker-name-prefix:` worker thread name prefix, default to `worker-`: `worker-1` `worker-2`....
   * `:queue-size:` max requests queued waiting for thread pool to compute response before rejecting, 503(Service Unavailable) is returned to client if queue is full, default to 20K
   * `:max-body:` length limit for request body in bytes, 413(Request Entity Too Large) is returned if request exceeds this limit, default to 8388608(8M)
   * `:max-line:` length limit for HTTP initial line and per header, 414(Request-URI Too Long) will be returned if exceeding this limit, default to 8192(8K), [relevant discussion on Stack Overflow](http://stackoverflow.com/questions/417142/what-is-the-maximum-length-of-a-url)

### Stop/Restart Server

`run-server` returns a function that stops the server, which can take an optional timeout(ms) param to wait for existing requests to be finished.

    (defn app [req]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    "hello HTTP!"})

    (defonce server (atom nil))

    (defn stop-server []
      (when-not (nil? @server)
        ;; graceful shutdown: wait 100ms for existing requests to be finished
        ;; :timeout is optional, when no timeout, stop immediately
        (@server :timeout 100)
        (reset! server nil)))

(defn -main [& args]
  ;; The #' is useful when you want to hot-reload code
  ;; You may want to take a look: https://github.com/clojure/tools.namespace
  ;; and http://http-kit.org/migration.html#reload
  (reset! server (run-server #'app {:port 8080})))

### Unified Async/Websocket API

*The with-channel API is not compatible with the RC releases. The new one is better and much easier to understand and use. The old documentation is [here](http://www.http-kit.org/server_old.html)*

Unified asynchronous channel interface for HTTP (streaming or long-polling) and WebSocket.

Channel defines the following contract:

   * `open?`: Returns true iff channel is open.
   * `close`: Closes the channel. Idempotent: returns true if the channel was actually closed, or false if it was already closed.
   * `websocket?`: Returns true iff channel is a WebSocket.
   * `send!`: Sends data to client and returns true if the data was successfully written to the output queue, or false if the channel is closed. Normally, checking the returned value is not needed. This function returns immediately (does not block).   *Data is sent directly to the client, NO RING MIDDLEWARE IS APPLIED.* Data form: {:headers _ :status _ :body _} or just body. Note that :headers and :status will be stripped for WebSockets and for HTTP streaming responses after the first.
   * `on-receive`: Sets handler (fn [message-string || byte[]) for notification of client WebSocket messages. Message ordering is guaranteed by server.
   * `on-close`: Sets handler (fn [status]) for notification of channel being closed by the server or client. Handler will be invoked at most once. Useful for clean-up. Status can be `:normal`, `:going-away`, `:protocol-error`, `:unsupported`, `:unknown`, `:server-close`, `:client-close`

    (defn handler [req]
      (with-channel req channel              ; get the channel
        ;; communicate with client using method defined above
        (on-close channel (fn [status]
                            (println "channel closed")))
        (if (websocket? channel)
          (println "WebSocket channel")
          (println "HTTP channel"))
        (on-receive channel (fn [data]       ; data received from client
               ;; An optional param can pass to send!: close-after-send?
               ;; When unspecified, `close-after-send?` defaults to true for HTTP channels
               ;; and false for WebSocket.  (send! channel data close-after-send?)
                              (send! channel data))))) ; data is sent directly to the client
    (run-server handler {:port 8080})

### HTTP Streaming example

   * First call of `send!`, sends HTTP status and Headers to client
   * After the first, `send!` sends a chunk to client
   * `close` sends an empty chunk to client, marking the end of the response
   * Client close notification printed via `on-close
    

    (require '[org.httpkit.timer :as timer]) ; Make the org.httpkit.timer namespace accesible via timer

    (defn handler [request]
      (server/with-channel request channel
        (on-close channel (fn [status] (println "channel closed, " status)))
        (loop [id 0]
          (when (< id 10)
            (timer/schedule-task (* id 200) ;; send a message every 200ms
                           (send! channel (str "message from server #" id) false)) ; false => don't close after send
            (recur (inc id))))
        (timer/schedule-task 10000 (close channel)))) ;; close in 10s.

    ;;; open you browser http://127.0.0.1:9090, a new message show up every 200ms
    (server/run-server handler {:port 9090})

### Long polling example

Long polling is very much like streaming

[chat-polling](https://github.com/http-kit/chat-polling) is a realtime chat example of using polling

    (def channel-hub (atom {}))

    (defn handler [request]
      (server/with-channel request channel
        ;; Store the channel somewhere, and use it to send response to client when interesting event happens
        (swap! channel-hub assoc channel request)
        (on-close channel (fn [status]
                            ;; remove from hub when channel get closed
                            (swap! channel-hub dissoc channel)))))

    (on-some-event                          ;; send data to client
     (doseq [channel (keys @channel-hub)]
       (send! channel {:status 200
                       :headers {"Content-Type" "application/json; charset=utf-8"}
                       :body data})))

    (server/run-server handler {:port 9090})

### WebSocket example

   * Two-way communication between client and server
   * Can easily degrade to HTTP long polling/streaming, due to the unified API
   * `send!` with `java.lang.String`, a text frame assembled and sent to client
   * `send!` with `java.io.InputStream` or `byte[]`, a binary frame assembled and sent to client
   * For WebSocket Secure connection, one option is [stud](https://github.com/bumptech/stud) (self-signed certificate may not work with websocket). [Nginx](http://nginx.com/news/nginx-websockets.html) can do it, too.

`(defn handler [request]
  (server/with-channel request channel
    (on-close channel (fn [status] (println "channel closed: " status)))
    (on-receive channel (fn [data] ;; echo it back
                          (send! channel data)))))

(server/run-server handler {:port 9090})`

### Control WebSocket handshake

The `with-channel` does the WebSocket handshake automatically. In case if you want to control it, e.g., to support WebSocket subprotocol, [here](https://github.com/http-kit/http-kit/issues/64) is a workaround. cgmartin's [gist](https://gist.github.com/cgmartin/5880732) is a good place to get inspired.

### Routing with Compojure

[Compojure](https://github.com/weavejester/compojure) can be used to do the routing, based on uri and method

(:use [compojure.route :only [files not-found]]
      [compojure.core :only [defroutes GET POST DELETE ANY context]]
      org.httpkit.server)

(defn show-landing-page [req] ;; ordinary clojure function, accepts a request map, returns a response map
  ;; return landing page's html string. Using template library is a good idea:
  ;; mustache (https://github.com/shenfeng/mustache.clj, https://github.com/fhd/clostache...)
  ;; enlive (https://github.com/cgrand/enlive)
  ;; hiccup(https://github.com/weavejester/hiccup)
  )

(defn update-userinfo [req]          ;; ordinary clojure function
  (let [user-id (-> req :params :id)    ; param from uri
        password (-> req :params :password)] ; form param
    ....
    ))

    (defroutes all-routes
      (GET "/" [] show-landing-page)
      (GET "/ws" [] chat-handler)     ;; websocket
      (GET "/async" [] async-handler) ;; asynchronous(long polling)
      (context "/user/:id" []
               (GET / [] get-user-by-id)
               (POST / [] update-userinfo))
      (files "/static/") ;; static file url prefix /static, in `public` folder
      (not-found "<p>Page not found.</p>")) ;; all other, return 404

    (server/run-server all-routes {:port 8080})
  

### Recommended server deployment

http-kit runs alone happily, handy for development and quick deployment. Use of a reverse proxy like [Nginx](http://wiki.nginx.org/Main), [Lighthttpd](http://www.lighttpd.net/), etc in serious production is encouraged. They can also be used to [add https support](http://www.http-kit.org/migration.html#https).

   * They are fast and heavily optimized for static content.
   * They can be configured to compress the content sent to browsers

Sample Nginx configration:

    upstream http_backend {
        server 127.0.0.1:8090;  # http-kit listen on 8090
        # put more servers here for load balancing
        # keepalive(resue TCP connection) improves performance
        keepalive 32;  # both http-kit and nginx are good at concurrency
    }

    server {
        location /static/ {  # static content
            alias   /var/www/xxxx/public/;
        }
        location / {
            proxy_pass  http://http_backend;

            # tell http-kit to keep the connection
            proxy_http_version 1.1;
            proxy_set_header Connection "";

            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header Host $http_host;

            access_log  /var/log/nginx/xxxx.access.log;
        }
    }
