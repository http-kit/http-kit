# Http kit

Event driven HTTP server and HTTP client in java and clojure, only
depend on JDK and Clojure.

I write it for the http server and http client of
[Rssminer](http://rssminer.net)


# Design goal

* Clean compact code, simple and correct is my goal. Feature rich is
  not desired.
* Asynchronous.
* Memory efficient. Memory is cheap, but anyway, I will do my best to
  save it.
* Handle timeout correctly. The server is intented to live behind
  Nginx, no timeout handing. The client fight alone, timeout should be
  handed correctly.
* Support Socks proxy. `SSH -D` create a Socks server, in china, proxy
  is a must.

# Usage

## HTTP server

TODO
