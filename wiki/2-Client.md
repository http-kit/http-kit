# Getting started

Please see the [legacy website](https://http-kit.github.io/), though please note that it may contain outdated information.

Contributors are in the process of importing and updating info from there to this wiki. Help **very welcome!**

# Advanced topics

## Server Name Indication (SNI)

http-kit 2.4+ supports client SNI on Java 8+.

Support is **automatically enabled** for http-kit 2.7+.  
Or support can be manually enabled by using the bundled SNI client:

```clojure
  (:require [org.httpkit.sni-client :as sni-client])

  ;; Change default client for your whole application:
  (alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

  ;; or temporarily change default client for a particular thread context:
  (binding [org.httpkit.client/*default-client* sni-client/default-client]
    <...>)
```

See [`org.httpkit.client/*default-client*`](http://http-kit.github.io/http-kit/org.httpkit.client.html#var-*default-client*) for more details.

If you're seeing `javax.net.ssl.SSLHandshakeException: Received fatal alert: handshake_failure` errors, there's a good chance that your client doesn't have SNI support enabled.

## Unix Domain Sockets (UDS)

http-kit 2.7+ supports server+client UDS on Java 16+.  
To use, plug in appropriate `java.net.SocketAddress` and `java.nio.channels.SocketChannel` constructor fns:

```clojure
(require '[org.httpkit.client :as hk-client])

(let [my-uds-path "/tmp/test.sock"
      my-client
      (hk-client/make-client
        {:address-finder  (fn [_uri]     (UnixDomainSocketAddress/of my-uds-path))
         :channel-factory (fn [_address] (SocketChannel/open StandardProtocolFamily/UNIX))})]

  (hk-client/get "http://foobar" {:client my-client}))
```

See [`make-client`](http://http-kit.github.io/http-kit/org.httpkit.client.html#var-make-client) for more info.