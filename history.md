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

### 2.0(not released yet)

HTTP client:
   1. :filter option
   2. async request with callback, in callback, a async request is issued
