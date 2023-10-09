# Getting started

```clojure
(ns my-ns
  (:require [org.httpkit.client :as hk-client])
```

Like http-kit server, http-kit client uses a lightweight and efficient event-driven, non-blocking I/O model that offers:

 - An easy-to-use API modelled after [clj-http](https://github.com/dakrone/clj-http)
 - Easy concurrency via promises
 - Efficient HTTPS support
 - Per-request timeouts and keep-alive

## Basics

### Using promises

```clojure
;; Returns ~immediately (after DNS lookup)
(def resp-promise (hk-client/get "http://host.com/path"))

;; Deref to block for result:
@resp-promise ; or
(deref resp-promise 5000 :my-timeout-val)
```

To send 2 simultaneous requests:

```clojure
(let [resp1 (hk-client/get "http://http-kit.org/")
      resp2 (hk-client/get "http://clojure.org/")]
  (println "Response 1's status: " (:status @resp1))
  (println "Response 2's status: " (:status @resp2)))
```

### Using callbacks

```clojure
(hk-client/get "http://host.com/path"
  {:timeout      200 ; msecs
   :basic-auth   ["user" "pass"]
   :query-params {:param "value" :param2 ["value1" "value2"]}
   :user-agent   "User-Agent-string"
   :headers      {"X-Header" "Value"}}

   (fn async-callback [{:keys [status headers body error]}]
     (if error
       (println "Failed, exception is " error)
       (println "Async HTTP GET: " status))))
```

Tip: all request opts will be passed to your callback, so you can include state in your opts map for convenience:

```clojure
(let [time (System/currentTimeMillis)]
  (hk-client/get "http://http-kit.org" {:my-start-time time} callback))

(defn callback [{:keys [status headers body error opts]}]
  ;; opts will include all keys from request call:
  (let [{:keys [method url my-start-time]} opts]
    (println method url "status" status "in"
      (- (System/currentTimeMillis) my-start-time) "ms")))
```

## Persistent connections

http-kit client uses HTTP/S keep-alive by default, keeping idle connections for 120s. This can be configured with the `:keepalive` option:

```clojure
@(hk-client/get "http://http-kit.org" {:keepalive 30000}) ; 30s keep-alive

;; Will reuse the previous TCP connection
@(hk-client/get "http://http-kit.org" {:keepalive 30000})

@(hk-client/get "http://http-kit.org" {:keepalive -1}) ; Disable keep-alive
```

## Output coercion

```clojure
;; Get the body as a byte stream
(hk-client/get "http://site.com/favicon.ico" {:as :stream}
  (fn [{:keys [status headers body error opts]}]
    ;; body is a `java.io.InputStream`
    ))

;; Or as a byte-array
(hk-client/get "http://site.com/favicon.ico" {:as :byte-array}
  (fn [{:keys [status headers body error opts]}]
    ;; body is a byte[]
    ))

;; Or a string
(hk-client/get "http://site.com/string.txt" {:as :text}
  (fn [{:keys [status headers body error opts]}]
    ;; body is a `java.lang.String`
    ))

;; Try to automatically coerce the output based on the Content-Type header
;; (currently supports :stream and :text with auto charset detection):
(hk-client/get "http://site.com/string.txt" {:as :auto})
```

## Nested params

`http-kit` supports nested params, inspired by `clj-http`:

```clojure
{:query-params {:a {:b {:c 5} :e {:f 6}}}} => "a[e][f]=6&a[b][c]=5"
```

Both accomplish this by encoding the nested data structure and expecting the server to understand the encoding. This is not robust so the recommended usage is to instead do the encoding and decoding explicitly with an encoding of your choice (e.g. JSON or EDN):

```clojure
(require '[clojure.data.json :as json])

(hk-client/post "http://your-server/api"
  ;; Encode nested params with JSON:
  {:query-params {:a (json/write-str {:b {:c 5} :e {:f 6}})}})

;; On server side: decide with `json/read-str`, etc.
```

## Other request options

See the `hk-client/request` docstring for more info on the extensive options:

```clojure
(hk-client/request
  {:url "http://http-kit.org/"
   :method :get ; :post :put :head, etc...
   :headers {"X-header" "value" "X-Api-Version" "2"}

   :query-params {"q" "foo, bar"} ; "Nested" query parameters are also supported
   :form-params  {"q" "foo, bar"} ; Like query-params but sent in the body
   :body (json/encode {"key" "value"}) ; E.g. for JSON Content-Type

   :basic-auth ["user" "pass"]
   :keepalive 3000 ; Keep the TCP connection for 3000 msecs
   :timeout 1000   ; Connection and read timeout of 1000 msecs

   :user-agent "User-Agent string"
   :oauth-token "your-token"
   :filter (hk-client/max-body-filter (* 1024 100)) ;; reject if body is more than 100k
   :insecure? true ; Need to contact a server with an untrusted SSL cert?

   :max-redirects 10 ; Max redirects to follow
   :follow-redirects false ; Whether to follow 301/302 redirects (default true)
                           ; :trace-redirects key in response will contain chain of
                           ; redirects followed

   ;; File upload example:
   ;; :content can be a `String`, `java.io.File`, or `java.io.InputStream`.
   ;; All content will be read before sending to server so content should be small (few MB):
   :multipart
   [{:name "comment" :content "httpkit's project.clj"}
    {:name "file" :content (clojure.java.io/file "project.clj") :filename "project.clj"}])
```

`hk-client/get` `hk-client/post` etc, are all built on top of `hk-client/request`, so, these options apply to them too

# Advanced topics

## Mocking requests in tests

You can use [http-kit-fake](https://github.com/d11wtq/http-kit-fake) to easily stub/simulate request calls, etc.:

```clojure
(with-fake-http ["http://http-kit.org/" "a fake response"]
  (hk-client/get {:url "http://http-kit.org/"})) ; promise wrapping the faked response
```

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
