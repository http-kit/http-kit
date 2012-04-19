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

# Why

I write it for the HTTP server and HTTP client of
[Rssminer](http://rssminer.net)

* Efficiently fetch feeds from Web.
* Fast proxy some sites for Chinese user.
* Rssminer need to be fast.

# Goal
* Clean compact code.
* Asynchronous.
* Memory efficient. Memory is cheap, but anyway, I will do my best to
  save it.
* Support Socks proxy. `SSH -D` create a Socks server, in china, proxy
  is a must.

# Limitation

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

# Usage

## HTTP server

TODO
