## 2.1.17 (2014/2/16)
HTTP Server:
    1. Allow max websocket message size configrable: max-ws option
    2. #121 return the local-port of the server by meta
HTTP Client:
    1. #110 Stringify headers in the client. (thanks cursork)
    2. #106 Fixed :multipart clobbering Authorization, User-Agent headers (thanks christianromney)

## 2.1.16 (2014/1/3)
HTTP Client:
    follow 301, 302, 303, 307, 308 properly (thanks paulbutcher)

## 2.1.15 (2014/1/1)
HTTP client:
  1. New feature: support automatically follow redirects
  2. New feature: support multipart/form-data file upload

## 2.1.14 (2013/12/23)
HTTP client:
  Fix #98 Strange timeout bug: SocketChannel.connect() may return true if the connection is established immediately, then the OP_CONNECT event will not be tiggered again. (thanks @cannedprimates)

## 2.1.13 (2013/10/19)
  Allow callback to be a multimethod for HTTP requests. (thanks @jaley)

## 2.1.12 (2013/10/11)
  Fix 84. 1. client sent Connection: Close => server, server try to streaming the response, server close the connection after first write, which makes a bad streaming. (thanks @rufoa)

## 2.1.11 (2013/9/10)
  the function return by calling run-server, than can be use to stop the server can now take an optional timeout(ms)
  param to wait exsiting requests to be finished, like (f :timeout 100) (thanks @gordonsyme)

## 2.1.10 (2013/8/20)
  fix 80: more robust websocket decoder, behave well even if client send one byte at a time

## 2.1.9 (2013/8/16)
  HTTP Header, properly handle multiple values with same key. fix #79

## 2.1.8 (2013/7/22)
  busy server with small RAM may OOM: do not fail, see #71

## 2.1.7 (2013/7/19)
  fix #70 follow clj-http's way of encoding URI

## 2.1.6 (2013/7/8)
  Accept ByteBuffer as the :body, not in the standard Ring Spec, but can make zero copy possbile, if performance is critical. Also useful if ByteBuffer get, just need to sent to another process

## 2.1.5 (2013/7/6)
HTTP Server:
   1. Fix #68, Loop of Close frames

## 2.1.4 (2013/6/13)
HTTP Client:
   1. Fix #60 By jjcomer, Add Oauth request header helper oauth-token
   2. Fix #62 Add HTTPS client `insecure?` support to allow self-signed certificates to function

## 2.1.3 (2013/5/17)
HTTP Client:
   1. Fix #52: Form-params support dictionaries (nested param)

## 2.1.2 (2013/5/15)
HTTP Client:
   1. Fix #52: 204 No Content responses from Jetty always timeout
   2. Do not add Accept header if one is already defined for the request, by Jeffrey Charles
   3. No need to create SSLEngine for http request

## 2.1.1 (2013/5/6)
HTTP Server:
   Fix #47: Large websocket requests get corrupted

## 2.1.0 (2013/5/3)
HTTP server:
   1. Fix: for IPv6 address <Thomas Heller>
   2. Fix: HTTP streaming does not sent keep-alive header for HTTP 1.0 keep-alived request
   3. Saving RAM by using HeaderMap and Unsafe
   4. Get rid of reflection warning

HTTP client:
   1. Support HTTPS. ~110k of RAM for issueing a HTTPS request
   2. Check to make sure url host is not null, fix a possible NPE
   3. Output coercion: :as option, accepted :auto :text :stream :byte-array.
   4. application/xml is a text response
   5. FIX: handle buggy web servers returning uncompliant Status-Line <Laszlo Toeroek>

## 2.0.1 (2013/4/12)
   1. Performance improvement. About 50% ~ 90% when benchmark due to IO model change: One IO thread => On thread reading, decoding + many threads writing

## 2.0.0 (2013/3/29)
   1. Unify WebSocket and HTTP long polling/streaming with Channel protocol and with-channel (**API breaks with the RC**)
   2. WebSocket support sending and receiving binary frame with byte[]
   3. Support HTTP streaming
   4. WebSocket message ordering is guaranteed by server

## 2.0-rc4 (2013/2/9)
   1. fix a possible CPU 100% usage

## 2.0-rc3 (2013/2/8)

   1. cancelable timer service to allow efficient schedule job for the future
   2. fix a possible IndexOutOfBoundsException when writing websocket response

## 2.0-rc2 (2013/2/2)

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

## 2.0-rc1 (2013/1/20)

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

## Older versions
* 1.2   Fix content-type for multipart/form-data
* 1.1.6 WebSocket support
* 1.1.3 Better syntax for defasync
* 1.1.1 HTTP client: allow custom ACCEPT_ENCODING, default gzip, deflate
* 1.1.0 defasync and async HTTP client clojure API
* 1.0.3  using UTF8 to encode HTTP response header: fix can not encode Chinese char
