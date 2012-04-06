# Http kit

A fast, asynchronous HTTP server and HTTP client in pure java
nio, Only depend on JDK.

I write it for the http server and http client of
[Rssminer](http://rssminer.net)


# Design goal

* Clean compact code, simple and correct is my goal. Feature rich is
  not desired.
* Asynchronous.
* Fast, I like fast. The server should handle 50k+ req/s if response
  with a few kilobytes of constant string.
* Memory efficient. Memory is cheap, but anyway, I will do my best to
  save it.
* Handle timeout correctly. The server is intented to live behind
  Nginx, no timeout handing. The client fight alone, timeout handing
  is a must, both connection timeout and read timeout
* Support Socks proxy. `SSH -D` create a Socks proxy, it's so handy.

# Usage

## HTTP server

TODO
