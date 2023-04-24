# http-kit

![github actions](https://github.com/http-kit/http-kit/actions/workflows/build.yml/badge.svg)

### Simple, high-performance event-driven HTTP client+server for Clojure

http-kit was created by [@shenfeng][], but is currently being maintained by its community.

A big thank-you to the [current contributors](https://github.com/http-kit/http-kit/graphs/contributors) for keeping the project going! **Additional contributors very welcome**: please ping me if you'd be interested in lending a hand.

\- [Peter Taoussanis][@ptaoussanis]

## Resources

1. [Wiki][] - **community documentation** 👈
1. [Release info][] - releases and changes
1. [API docs][] - auto-generated API docs
1. [GitHub issues][] - for support requests and [contributions][], etc.

## Features

- **Ring compliant**: http-kit is an [(almost)](http://http-kit.github.io/migration.html) drop-in replacement for the standard Ring Jetty adapter. So you can use it with all your current libraries (e.g. [Compojure](http://http-kit.github.io/server.html#routing)) and middleware.

- **High performance**: Using an event-driven architecture like Nginx, HTTP-kit is [very, very fast](https://github.com/ptaoussanis/clojure-web-server-benchmarks). It comfortably handles tens of thousands of requests/sec on even midrange hardware. [Here](http://www.techempower.com/benchmarks/#section=data-r3) is another test about how it stacks up with others.

- **High concurrency**: It's not only fast, but [efficient](http://http-kit.github.io/600k-concurrent-connection-http-kit.html)! Each connection costs nothing but a few kB of memory. RAM usage grows O(n) with connections.

- **Clean, simple, small**: Written from the ground-up to be lean, the entire client/server is available as a single ~90kB JAR with zero dependencies and [~3k lines](http://http-kit.github.io/http-kit-clean-small.html) of (mostly Java) code.

- **Sync or async**: Synchronous is simple. Asynchronous is fast & flexible. With http-kit you get the best of both with a [simple API](http://http-kit.github.io/client.html) that lets you mix & match to best fit your use case.

- **WebSockets and Comet**: With great out-the-box support for both [WebSockets](http://http-kit.github.io/server.html#websocket) and efficient handling of [long-held HTTP requests](http://http-kit.github.io/server.html#async), realtime web applications are a breeze to write.

There's also some additional info at the legacy [project website][], though please note that this is currently unmaintained and quite outdated.

## License

Copyright &copy; 2012-2023 [@shenfeng][] and contributors. Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

[Wiki]: ../../wiki
[Release info]: ../../releases
[API docs]: http://http-kit.github.io/http-kit/
[GitHub issues]: ../../issues
[contributions]: ../../blob/master/CONTRIBUTING.md
[@shenfeng]: https://github.com/shenfeng
[@ptaoussanis]: https://github.com/ptaoussanis
[project website]: https://http-kit.github.io