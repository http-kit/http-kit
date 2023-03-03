# HTTP Kit

![github actions](https://github.com/http-kit/http-kit/actions/workflows/build.yml/badge.svg)

### A simple, high-performance event-driven HTTP client+server for Clojure

**[CHANGELOG][]** | [API][] | current [Break Version][]:

```clojure
[http-kit "2.6.0"]  ; Published by contributors, see CHANGELOG for details (stable)
[http-kit "2.1.19"] ; Legacy, published by @shenfeng
```

## Project status

http-kit's author ([@shenfeng][]) unfortunately hasn't had much time to maintain http-kit recently. To help out I'll be doing basic issue triage, accepting minor/obvious PRs, etc.

A big thank you to the **[current contributors](https://github.com/http-kit/http-kit/graphs/contributors)** for keeping the project going! **Additional contributors welcome**: please ping me if you'd be interested in lending a hand.

See the (unmaintained, outdated) [project website][] for original documentation, examples, benchmarks, etc.

\- [@ptaoussanis][]

## Features

- **Ring compliant**: HTTP Kit is an [(almost)](http://http-kit.github.io/migration.html) drop-in replacement for the standard Ring Jetty adapter. So you can use it with all your current libraries (e.g. [Compojure](http://http-kit.github.io/server.html#routing)) and middleware.

- **High performance**: Using an event-driven architecture like Nginx, HTTP-kit is [very, very fast](https://github.com/ptaoussanis/clojure-web-server-benchmarks). It comfortably handles tens of thousands of requests/sec on even midrange hardware. [Here](http://www.techempower.com/benchmarks/#section=data-r3) is another test about how it stacks up with others.

- **High concurrency**: It's not only fast, but [efficient](http://http-kit.github.io/600k-concurrent-connection-http-kit.html)! Each connection costs nothing but a few kB of memory. RAM usage grows O(n) with connections.

- **Clean, simple, small**: Written from the ground-up to be lean, the entire client/server is available as a single ~90kB JAR with zero dependencies and [~3k lines](http://http-kit.github.io/http-kit-clean-small.html) of (mostly Java) code.

- **Sync or async**: Synchronous is simple. Asynchronous is fast & flexible. With HTTP Kit you get the best of both with a [simple API](http://http-kit.github.io/client.html) that lets you mix & match to best fit your use case.

- **WebSockets and Comet**: With great out-the-box support for both [WebSockets](http://http-kit.github.io/server.html#websocket) and efficient handling of [long-held HTTP requests](http://http-kit.github.io/server.html#async), realtime web applications are a breeze to write.

## Enabling client SNI support (DISABLED BY DEFAULT)

To retain backwards-compatibility with JVMs < 8, http-kit client's **SNI support is DISABLED by default**.

> Common cause of: `javax.net.ssl.SSLHandshakeException: Received fatal alert: handshake_failure`

This default may be changed in a future breaking release. In the meantime, manually enabling support is easy:

```clojure
  (:require [org.httpkit.sni-client :as sni-client]) ; Needs Java >= 8, http-kit >= 2.4.0-alpha6

  ;; Change default client for your whole application:
  (alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

  ;; or temporarily change default client for a particular thread context:
  (binding [org.httpkit.client/*default-client* sni-client/default-client]
    <...>)
```
See `org.httpkit.client/*default-client*` docstring for more details.

## Unix Domain Sockets (UDSs)

http-kit >= 2.7 supports Unix Domain Sockets for both clients and servers  when running on [Java >= 16](https://openjdk.org/jeps/380).

To use UDSs, plug in appropriate `java.net.SocketAddress` and `java.nio.channels.SocketChannel` constructor fns:

### UDS example: client

```clojure
(require '[org.httpkit.client :as hk-client])

(let [my-uds-path "/tmp/test.sock"
      my-client
      (hk-client/make-client
        {:address-finder  (fn [_uri]     (UnixDomainSocketAddress/of my-uds-path))
         :channel-factory (fn [_address] (SocketChannel/open StandardProtocolFamily/UNIX))})]

  (hk-client/get "http://foobar" {:client my-client}))
```

### UDS example: server

```clojure
(require '[org.http-kit.server :as hk-server])

(let [my-uds-path "/tmp/test.sock"
      my-server
      (hk-server/run-server my-routes
        {:address-finder  (fn []         (UnixDomainSocketAddress/of my-uds-path))
         :channel-factory (fn [_address] (ServerSocketChannel/open StandardProtocolFamily/UNIX))})]
  <...>
  )
```

See the [`make-client`](http://http-kit.github.io/http-kit/org.httpkit.client.html#var-make-client) and [`run-server`](http://http-kit.github.io/http-kit/org.httpkit.server.html#var-run-server) docstrings for more info.

## GraalVM Native Image

http-kit server and client are compatible with GraalVM's native-image compiler.

To ensure the image can build, provide the following options to the native-image compiler:

### Reflection

In your reflection-config.json

```json
{"name": "java.lang.reflect.AccessibleObject",
 "methods" : [{"name":"canAccess"}]}
```

### Class initialization

As of version `2.5.2` add the following flags:

```sh
--initialize-at-run-time=org.httpkit.client.ClientSslEngineFactory\$SSLHolder
```

## Hack locally

http-kit is hacker friendly: zero dependencies, written from the ground-up with only ~3.5k lines of code (including Java).

```sh
# Modify as you want, unit tests back you up:
lein test

# May be useful (more info), see `server_test.clj`:
./scripts/start_test_server

# Some numbers on how fast can http-kit's client can run:
lein test :benchmark
```

### Contact & contribution

Please use the [GitHub issues page](https://github.com/http-kit/http-kit/issues) for feature suggestions, bug reports, or general discussions. Current contributors are listed [here](https://github.com/http-kit/http-kit/graphs/contributors). The [project website][] is also on GitHub.

## License

Copyright &copy; 2012-2023 [@shenfeng][] and contributors. Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

[CHANGELOG]: https://github.com/http-kit/http-kit/releases
[API]: http://http-kit.github.io/http-kit/
[@shenfeng]: https://github.com/shenfeng
[@ptaoussanis]: https://github.com/ptaoussanis
[project website]: https://http-kit.github.io
[Break Version]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md
