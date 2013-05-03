## Update history

* 1.0.3  using UTF8 to encode HTTP response header: fix can not encode Chinese char
* 1.1.0 defasync and async HTTP client clojure API
* 1.1.1 HTTP client: allow custom ACCEPT_ENCODING, default gzip, deflate
* 1.1.3 Better syntax for defasync
* 1.1.6 WebSocket support
* 1.2   Fix content-type for multipart/form-data


#### 2.0-rc1 (2013/1/20)

HTTP server:
  1. Support HTTP/1.0 keep-alive
  2. Better error reporting
  3. Better serving larget file(mmap),
  4. `:queue-size` option to protect high traffic web server
  5. API redisign: `async-response` and `if-ws-request` for better flexibility

HTTP client:
  1. API redesign: by using promise and callback, support both sync and async call
  2. Timeout per request
  3. Support keep-alive

### 2.0-rc2 (2013/2/2)

   1. package rename me.shenfeng.http => org.httpkit
   2. using semantic version
   3. more unit test

HTTP client:
   1. :filter option and max-body-filter
   2. fix potential deadlock: in async request's callback, a sync request is issued
   3. fix url double percent encoding issue
   5. fix deflated body is not properly decompressed
   6. fix keep-alive issue, add unit test to make sure it always works as expected
   7. :body can be nil, string, file, inputstream, iseq, just as ring response's body

HTTP server:
   1. properly pass :head and other method for :request-method
   2. save memory when decoding request by using a reasonable buffer size, increase as necessary

### 2.0-rc2 (2013/2/8)

   1. cancelable timer service to allow efficient schedule job for the future
   2. fix a possible IndexOutOfBoundsException when writing websocket response

### 2.0-rc2 (2013/2/9)
   1. fix a possible CPU 100% usage

### 2.0.0 (2013/3/29)
   1. Unify WebSocket and HTTP long polling/streaming with Channel protocol and with-channel (API breaks with the RC)
   2. WebSocket support sending and receiving binary frame with byte[]
   3. Support HTTP streaming
   4. WebSocket message ordering is guaranteed by server

### 2.0.1 (2013/4/12)
   1. Performance improvement. About 50% ~ 90% when benchmark due to IO model change: One IO thread => On thread reading, decoding + many threads writing

### 2.1.0 (2013/5/3)
HTTP server:
   1. Fix for IPv6 address <Thomas Heller>
   2. Saving RAM by using HeaderMap and Unsafe
   3. Fix HTTP streaming does not sent keep-alive header for HTTP 1.0 keep-alived request
   4. Get rid of reflection warning

HTTP client:
   1. Support HTTPS
   2. Check to make sure host is not null
   3. Output coercion: :as option, accepted :auto :text :stream :byte-array.
   4. application/xml is a text response
   5. FIX: handle buggy web servers returning uncompliant Status-Line <Laszlo Toeroek>
