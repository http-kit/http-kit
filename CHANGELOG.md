> This project uses [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md) as of **Sep 18, 2020**.

## v2.5.0 (2020 Sep 18)

```clojure
[http-kit "2.5.0"]
```

> **Bumps minimum JVM version from 1.6 to 1.7**. _Should_ otherwise be non-breaking.  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

Identical to `v2.5.0-RC1`.

#### Changes since `2.4.0`

* **BREAKING**: bump minimum JVM version from 1.6 to 1.7
* [#438 #439][Server] Stop using `sun.misc.Unsafe` (@kirked)

#### New since `2.4.0`

* [#434][Client] GraalVM Native Image Compatibility: move SSL initialisation to constructor (@alekcz)
* [#433 #432 #129] [Server] Configurable server header (@barkanido)
* [#441][Server] Add 1-arity `server-stop!`

#### Fixes since `2.4.0`

* [#429] Fix flaky server-status tests
* [Server][Tests] Fix lint issue with newer JDKs


## v2.5.0-RC1 (2020 Sep 10)

```clojure
[http-kit "2.5.0-RC1"]
```

> **Bumps minimum JVM version from 1.6 to 1.7**. _Should_ otherwise be non-breaking.  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

#### Changes since `2.4.0`

* **BREAKING**: bump minimum JVM version from 1.6 to 1.7
* [#438 #439][Server] Stop using `sun.misc.Unsafe` (@kirked)

#### New since `2.4.0`

* [#434][Client] GraalVM Native Image Compatibility: move SSL initialisation to constructor (@alekcz)
* [#433 #432 #129] [Server] Configurable server header (@barkanido)
* [#441][Server] Add 1-arity `server-stop!`

#### Fixes since `2.4.0`

* [#429] Fix flaky server-status tests
* [Server][Tests] Fix lint issue with newer JDKs


## 2.4.0 (2020 Jul 30)

```clojure
[http-kit "2.4.0"]
```

Identical to `2.4.0-RC1`, `2.4.0-beta1`.

Upgrade from `2.3.x` **should be non-breaking**, but please evaluate before using in production (and please report any problems!).

[Server] **DEPRECATION NOTICE**: `with-channel` macro has been deprecated due to possible race conditions [#318]. Please prefer new `as-channel` fn instead. Migration is straightforward; please see `as-channel` docstring for usage info.

#### Changes since `2.3.0`

* [#318][#391] [Server] **DEPRECATE** `with-channel`, add `as-channel`
* [#399][Server] Graceful Shutdown (@glenjamin)
* [#427] [Client] Improve performance of `prepare-request-headers` (@bsless)

#### New since `2.3.0`

* [#412 #383 #388] Java 11 support (@ryfow, @RokLenarcic)
* [#426 #422 #393 #334 #187] [Client] Add a drop-in SNI-capable client (@fpischedda, @jimpil)
* [#422] [Client] Provide a dynamic var to override default client (@fpischedda)
* [#318][#391] [Server] `as-channel`: add :init hook
* [Server] Optional new return value for `run-server`, new fns: `server-port`, `server-status`, `server-shutdown!`
* [#429 #186] [Server] `server-stop!` now returns a promise (@alekcz, @ptaoussanis)
* [#395] [Server] Add keywords to cover missing WebSocket status codes (@daviesian)
* [#374] [Client] Allow specification of local address when making requests (@snoe)
* [#379] [Server] Support byte[] responses (@ikitommi)
* [#389] [Client] Make `query-string` public (@kirked)
* [#400 #402] [Client] Add :none coercion option to return byte-array (@ethpran)
* [#403 #415] [Client] Add error message when no location header is present (@BadAlgorithm)

#### Fixes since `2.3.0`

* [#410 #419] [Server] Special-case the parsing for ipv6, if the host header starts with `[` (@blak3mill3r)
* [#384] [Server] Fix typo in `run-server` doc-string (@dsdolzhenko)


## 2.4.0-RC1 (2020 Jul 25)

```clojure
[http-kit "2.4.0-beta1"]
```

Identical to `2.4.0-beta1`.

## 2.4.0-beta1 (2020 Jul 21)

```clojure
[http-kit "2.4.0-beta1"]
```

Should be **non-breaking** but as usual, please evaluate before using in production (and please report any problems!).

Items new since `2.4.0-alpha6` prefixed with ++.

#### Changes since `2.3.0`

* [#318][#391] [Server][Experimental] Deprecate `with-channel`, add `as-channel`
* [#399][Server] Graceful Shutdown (@glenjamin)
* ++ [#427] [Client] Improve performance of `prepare-request-headers` (@bsless)

#### New since `2.3.0`

* [#412 #383 #388] Java 11 support (@ryfow, @RokLenarcic)
* [#426 #422 #393 #334 #187] [Client] Add a drop-in SNI-capable client (@fpischedda, @jimpil)
* [#422] [Client] Provide a dynamic var to override default client (@fpischedda)
* ++ [#318][#391] [Server] `as-channel`: add :init hook
* ++ [Server] New return value for `run-server`, new fns: `server-port`, `server-status`, `server-shutdown!`
* ++ [#429 #186] [Server] `server-stop!` now returns a promise (@alekcz, @ptaoussanis)
* [#395] [Server] Add keywords to cover missing WebSocket status codes (@daviesian)
* [#374] [Client] Allow specification of local address when making requests (@snoe)
* [#379] [Server] Support byte[] responses (@ikitommi)
* [#389] [Client] Make `query-string` public (@kirked)
* [#400 #402] [Client] Add :none coercion option to return byte-array (@ethpran)
* [#403 #415] [Client] Add error message when no location header is present (@BadAlgorithm)

#### Fixes since `2.3.0`

* [#410 #419] [Server] Special-case the parsing for ipv6, if the host header starts with `[` (@blak3mill3r)
* [#384] [Server] Fix typo in `run-server` doc-string (@dsdolzhenko)


## 2.4.0-alpha6 (2020 Feb 15)

```clojure
[http-kit "2.4.0-alpha6"]
```

> As usual, please evaluate before using in production (and **please report any problems!**).

#### Changes since 2.3.0

* [#318][#391] [Server][Experimental] Deprecate `with-channel`, add `as-channel`
* [#399][Server] Graceful Shutdown (@glenjamin)

#### New since 2.3.0

* [#412 #383 #388] Java 11 support (@ryfow, @RokLenarcic)
* [#426 #422 #393 #334 #187] [Client] Add a drop-in SNI-capable client (@fpischedda, @jimpil)
* [#422] [Client] Provide a dynamic var to override default client (@fpischedda)
* [#395] [Server] Add keywords to cover missing WebSocket status codes (@daviesian)
* [#374] [Client] Allow specification of local address when making requests (@snoe)
* [#379] [Server] Support byte[] responses (@ikitommi)
* [#389] [Client] Make `query-string` public (@kirked)
* [#400 #402] [Client] Add :none coercion option to return byte-array (@ethpran)
* [#403 #415] [Client] Add error message when no location header is present (@BadAlgorithm)

#### Fixes since 2.3.0

* [#410 #419] [Server] Special-case the parsing for ipv6, if the host header starts with `[` (@blak3mill3r)
* [#384] [Server] Fix typo in `run-server` doc-string (@dsdolzhenko)

## 2.4.0-alpha3 (2019 Jan 19)

```clojure
[http-kit "2.4.0-alpha3"]
```

> As usual, please evaluate before using in production (and **please report any problems!**).

#### Changes

* [#391 #318 #46] [Server] BREAKING: ensure WS on-receive is ready before first message arrives (@huahaiy)

#### New stuff

* [#383 #388] Java 11 support (@ryfow)
* [#374] [Client] Allow specification of local address when making requests (@snoe)
* [#379] [Server] Support byte[] responses (@ikitommi)

#### Fixes

* [#384] [Server] Fix typo in `run-server` doc-string (@dsdolzhenko)


## 2.3.0 (2018 Apr 21)

```clojure
[http-kit "2.3.0"]
```

This is a **major release** that *should* be non-breaking but **may require testing**. Please evaluate before using in production (and **please report any problems!**).

A big thank you to all the [contributors for this release](https://github.com/http-kit/http-kit/commits/master)!

\- Peter Taoussanis (@ptaoussanis)

> Listed changes are relative to **2.2.0**:

#### New stuff

* [#345 #372] Client: add deadlock-guard for callbacks (@Chouser), enabled by default (could be **BREAKING**, disable using `:deadlock-guard?` option).
* [#315] Client: now have separate `:connect-timeout` and `:idle-timeout` opts (@kmate)
* [#329] Client: support numbers in multipart messages, and throw on unkown multipart params types (@dmichulke)
* [#303] Client: replace :proxy-host, :proxy-port -> :proxy-url (@taso42)
* [#335] Client: allow custom SSLEngine config, make way for fixing #187 (@kumarshantanu)
* [#323 #333] Client, server: add metrics/logging instrumentation (@kumarshantanu)
* [#307 #231] Server: add :worker-pool opt (@kaibra)
* [#309 #310] Server: add missing 'MKCOL' http method enum (@zilti)
* [#300] Server: add getCount method to BytesInputStream (@MysteryMachine)

#### General improvements

* [#330] Server: don't override Date header if it's been set by application (@ryfow)
* [#341 #196] Server: increase max-line default value to conform to nginx defaults (@Kjir)
* Client: deref default-client ONLY-when client not specified (@kumarshantanu)
* [#353] Tidy up some tests (@glenford)

#### Fixes

* [#332 #322] Server: do not respond to unsolicited pong frames (@mikkosuonio)
* [#319] Server: fix the 'Close received after close' issue for WS implementation (@zhming0)
* [#356 #370] Client, server: fix `base64-encoder` for Java 9 (@claj @benalbrecht)
* [#361 #362] Client: fix bug in parsing logic for chunked responses (@rkaippully)

## 2.3.0-RC1 (2018 Apr 9)

```clojure
[http-kit "2.3.0-RC1"]
```

> Identical to `2.3.0-beta2`.

## 2.3.0-beta2 (2018 Mar 17)

```clojure
[http-kit "2.3.0-beta2"]
```

> This is a minor **hotfix release**

* [#356 #370] Fix `base64-encoder` for Java 9 (@claj @benalbrecht)

## 2.3.0-beta1 (2018 Mar 11)

```clojure
[http-kit "2.3.0-beta1"]
```

> This is a major **feature and maintenance release**. Please test carefully and report any bugs!

Changes since **2.2.0**:

#### New stuff

* [#315] Client: now have separate `:connect-timeout` and `:idle-timeout` opts (@kmate)
* [#307 #231] Server: add :worker-pool opt (@kaibra)
* [#309 #310] Server: add missing 'MKCOL' http method enum (@zilti)
* [#329] Client: support numbers in multipart messages, and throw on unkown multipart params types (@dmichulke)
* [#300] Server: add getCount method to BytesInputStream (@MysteryMachine)
* [#335] Make way for fixing #187 (@kumarshantanu)
* [#323 333] Metrics/logging instrumentation (@kumarshantanu)

#### General improvements

* [#330] Server: don't override Date header if it's been set by application (@ryfow)
* [#303] Client: replace :proxy-host, :proxy-port -> :proxy-url (@taso42)
* [#341 #196] Server: increase max-line default value to conform to nginx defaults (@Kjir)
* Client: deref default-client ONLY-when client not specified (@kumarshantanu)

#### Fixes

* [#332 #322] Server: do not respond to unsolicited pong frames (@mikkosuonio)
* [#319] Server: fix the 'Close received after close' issue for WS implementation (@zhming0)
* [#361 #362] Client: fix bug in parsing logic for chunked responses (@rkaippully)

## 2.3.0-alpha5 (2018 Feb 2)

```clojure
[http-kit "2.3.0-alpha5"]
```

> This is a minor **hotfix release**. Please test carefully and report any bugs!

* **Hotfix**: [#361 #362] Client: fix bug in parsing logic for chunked responses (@rkaippully)

## 2.3.0-alpha4 (2017 Sep 3)

```clojure
[http-kit "2.3.0-alpha4"]
```

> This is a minor **feature release**. Please test carefully and report any bugs!

#### New stuff

* [#335] Make way for fixing #187 (@kumarshantanu)
* [#323 333] Metrics/logging instrumentation (@kumarshantanu)

#### General improvements

* [#341 #196] Server: increase max-line default value to conform to nginx defaults (@Kjir)
* Client: deref default-client ONLY-when client not specified (@kumarshantanu)

## 2.3.0-alpha2 (2017 Apr 16)

```clojure
[http-kit "2.3.0-alpha2"]
```

> This is a major **feature and maintenance release**. Please test carefully and report any bugs!

#### New stuff

* [#315] Client: now have separate `:connect-timeout` and `:idle-timeout` opts (@kmate)
* [#307 #231] Server: add :worker-pool opt (@kaibra)
* [#309 #310] Server: add missing 'MKCOL' http method enum (@zilti)
* [#329] Client: support numbers in multipart messages, and throw on unkown multipart params types (@dmichulke)
* [#300] Server: add getCount method to BytesInputStream (@MysteryMachine)

#### General improvements

* [#330] Server: don't override Date header if it's been set by application (@ryfow)
* [#303] Client: replace :proxy-host, :proxy-port -> :proxy-url (@taso42)

#### Fixes

* [#332 #322] Server: do not respond to unsolicited pong frames (@mikkosuonio)
* [#319] Server: fix the 'Close received after close' issue for WS implementation (@zhming0)

## 2.2.0 (2016 Jul 12)

```clojure
[http-kit "2.2.0"]
```

This is the big one; the first **stable** http-kit release published by [contributors](https://github.com/http-kit/http-kit/commits/master)!

This should be a **non-breaking upgrade from 2.1.19** but, as always, please report any unexpected problems. Thanks!

\- Peter Taoussanis (@ptaoussanis)

> Changes since **2.1.19** (the last stable release published by @shenfeng)

#### New stuff

* [#236] Server: add proxy protocol support (@tendant)
* [#283] Server: allow overwriting "Server" response header (@skazhy)
* [#285] Server: add `send-websocket-handshake!` util for pre-handshake auth, etc.
* [#201] Client: add support for optional unsafe redirects (@dzaharee)
* [#275] Client: add proxy support (@davidkazlauskas, @jbristow)
* [#280 #281 #291] Client: allow https requests through proxy, tunneling (@jshaw86, @jbristow)
* [#297] Client: allow multipart entity to support byte arrays (@vincentjames501)
* [#297] Client: allow setting multipart content type (@vincentjames501)
* [#293] Client: add max connections support (@ryfow)

#### General improvements

* [#130] Server: can now respond to `HTTP_1.1/Expect: 100-continue` headers (@valentini)
* [#216] Server: optimization, remove an unnecessary operation (@songsd)
* [#217] Client: more accurate timeout handling (@mbarbon)
* [#234] Client: catch Throwable when delivering client response (@msassak)
* [#185] Client: do not set client mode on SSLEngine if already set (@izarov)
* [#261 #203] Client: updated documentation on request function (@javahippie)
* [#274 #272] Client: make HttpClient class non-final (@kumarshantanu)
* [#266] Project: fix #189 - include Java source files in the JAR (@kumarshantanu)

#### Fixes

* [#259 #227] Server: reset decoder after starting the close handshake for the websocket server (@venantius)
* [#258 #148] Server: remind the selector to wake up (@venantius)
* [#248] Server: correctly reset fragmentedOpCode on WS frame completion (@daviesian)
* [#250] Server: omit invalid headers and prevent possible exceptions (e.g., NullPointerException) (@Chienlung)
* [#255] Server: fixed empty sequence NPE, added test (@leblowl)
* [#264 #219] Client: set SO_KEEPALIVE and TCP_NODELAY for client socket (@jruthe)
* [#264 #209] Client: clear query and form params on 301-303 redirects (@jruthe)
* [#254] Server: byteBuffer is 0-based (@Thingographist)
* [#190] Server: transfer-Encoding is only supported in HTTP Version 1.1 (@jenshaase)

## 2.2.0-beta1 (2016 Jun 21)

```clojure
[http-kit "2.2.0-beta1"]
```

**Feature freeze**: this is expected to be the final release before `v2.2.0-RC1`.

> Changes since `*2.2.0-alpha2`:

### New stuff

* [#297] Client: allow multipart entity to support byte arrays (@vincentjames501)
* [#297] Client: allow setting multipart content type (@vincentjames501)
* [#293] Client: add max connections support (@ryfow)

## 2.2.0-alpha2 (2016 Jun 8)

```clojure
[http-kit "2.2.0-alpha2"]
```

This should be a non-breaking upgrade from **2.2.0-alpha1** but **please evaluate before using in production**.

Thank you to all [contributors for this release](https://github.com/http-kit/http-kit/commits/master)!

\- Peter Taoussanis (@ptaoussanis)

> Listed changes are relative to **2.2.0-alpha1**:

#### New stuff

* [#275] Client: add proxy support (@davidkazlauskas, @jbristow)
* [#280 #281 #291] Client: allow https requests through proxy, tunneling (@jshaw86, @jbristow)
* [#283] Server: allow overwriting "Server" response header (@skazhy)

#### General improvements

* [#274 #272] Make HttpClient class non-final (@kumarshantanu)

#### Fixes

* [#190] Transfer-Encoding is only supported in HTTP Version 1.1 (@jenshaase)


## 2.2.0-alpha1 (2016 Mar 3)

```clojure
[http-kit "2.2.0-alpha1"]
```

This is a **major release** that *should* be non-breaking but **may require testing**. Please evaluate before using in production (and **please report any problems!**).

A big thank you to all the [contributors for this release](https://github.com/http-kit/http-kit/commits/master)!

\- Peter Taoussanis (@ptaoussanis)

> Listed changes are relative to **2.1.19**:

#### New stuff

* [#236] Server enhancement: add proxy protocol support (@tendant)
* [#201] Client now supports optional unsafe redirects (@dzaharee)

#### General improvements

* [#130] Server can now respond to `HTTP_1.1/Expect: 100-continue` headers (@valentini)
* [#217] More accurate timeout handling (@mbarbon)
* [#234] Catch Throwable when delivering client response (@msassak)
* [#185] Do not set client mode on SSLEngine if already set (@izarov)
* [#261 #203] Updated documentation on request function (@javahippie)
* [#266] Fix #189 - include Java source files in the JAR (@kumarshantanu)
* [#216] Server optimization: remove an unnecessary operation (@songsd)

#### Bug fixes
* [#259 #227] Reset decoder after starting the close handshake for the websocket server (@venantius)
* [#258 #148] Remind the selector to wake up (@venantius)
* [#248] Correctly reset fragmentedOpCode on WS frame completion (@daviesian)
* [#250] Omit invalid headers and prevent possible exceptions (e.g., NullPointerException) (@Chienlung)
* [#255] Fixed empty sequence NPE, added test (@leblowl)
* [#264 #219] Set SO_KEEPALIVE and TCP_NODELAY for client socket (@jruthe)
* [#264 #209] Clear query and form params on 301-303 redirects (@jruthe)
* [#254] ByteBuffer is 0-based (@Thingographist)

## 2.1.21-alpha2 (2015 Dec 12)

```clojure
[http-kit "2.1.21-alpha2"]
```

> == 2.1.21-alpha1 (Clojars upload error)

HTTP Server:
  * [#217] More accurate timeout handling (@mbarbon)
  * [#130] Server can now respond to HTTP_1.1/Expect : 100-continue header once per request (@valentini)

HTTP Client:
  * [#201] Client: option to keep "unsafe" method on redirect (@dzaharee)

## 2.1.20 (2015 Dec 8)

```clojure
[com.taoensso.forks/http-kit "2.1.20"]
```

> This release was a **temporary fork**

HTTP Server:
  1. #86 Allow run-server being called without any opts (@djui)
  2. #185 Do not set client mode on SSLEngine if already set (@izarov)
  3. #234 Catch Throwable when delivering response (@msassak)
  4. #239 HTTP client: keep form params in multipart requests (@skazhy)
HTTP Client:
  No changes

## 2.1.19 (2014 Aug 25)
HTTP Server:
  1. #108 Duplicate headers cons'ed into list don't conform with ring spec
  2. #152 Exception when stopping server
  3. #155 Call on-close handlers on calling thread when threadpool is closed
HTTP Client:
  1. #149 Added methods required for CalDAV (@robbieh)
  2. #157 Default HttpClient callback thread pool is always one (@dlebrero)

## 2.1.18 (2014/4/18)
HTTP Server:
  1. #125 NPE on stop_server
  2. #127 only trust the computed Content-Length header
  3. #134  pong frame expects a ping frame as response
HTTP Client:
  1. #127 only trust the computed Content-Length header
  2. #131 Fix the client handling of empty Reason-Phrase (Thanks Pyry Jahkola)

## 2.1.17 (2014/2/16)
HTTP Server:
  1. Allow max websocket message size configurable: max-ws option
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
  Fix #98 Strange timeout bug: SocketChannel.connect() may return true if the connection is established immediately, then the OP_CONNECT event will not be triggered again. (thanks @cannedprimates)

## 2.1.13 (2013/10/19)
  Allow callback to be a multimethod for HTTP requests. (thanks @jaley)

## 2.1.12 (2013/10/11)
  Fix 84. 1. client sent Connection: Close => server, server try to streaming the response, server close the connection after first write, which makes a bad streaming. (thanks @rufoa)

## 2.1.11 (2013/9/10)
  the function return by calling run-server, than can be use to stop the server can now take an optional timeout(ms)
  param to wait existing requests to be finished, like (f :timeout 100) (thanks @gordonsyme)

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
   1. Support HTTPS. ~110k of RAM for issuing a HTTPS request
   2. Check to make sure url host is not null, fix a possible NPE
   3. Output coercion: :as option, accepted :auto :text :stream :byte-array.
   4. application/xml is a text response
   5. FIX: handle buggy web servers returning non-compliant Status-Line <Laszlo Toeroek>

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
  3. Better serving largest file(mmap),
  4. `:queue-size` option to protect high traffic web server
  5. API redesign: `async-response` and `if-ws-request` for better flexibility

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
