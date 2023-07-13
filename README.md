<span><img src="http-kit-logo.png" alt="http-kit" width="140"/></span>  
[**Documentation**](#documentation) | [Latest releases](#latest-releases) | [Get support][GitHub issues]

# http-kit

### Simple, high-performance event-driven HTTP client+server for Clojure

http-kit is a minimalist and highly efficient Ring-compatible HTTP client+server for Clojure.

It uses an event-driven architecture to support highly concurrent a/synchronous web applications, and features a simple unified API for WebSocket and HTTP long-polling/streaming.

## Latest release/s

- `2023-06-30` `2.7.0`: [changes](../../releases/tag/v2.7.0)

[![Main tests][Main tests SVG]][Main tests URL]
[![Graal tests][Graal tests SVG]][Graal tests URL]

See [here][GitHub releases] for earlier releases.

## Why http-kit?

> Links below may point to the unmaintained and outdated [legacy website](https://http-kit.github.io). Efforts are underway to migrate and update info from there on the new [community wiki][GitHub wiki].

- **Ring compliant**: http-kit is an [(almost)](https://http-kit.github.io/migration.html) drop-in replacement for the standard Ring Jetty adapter. So you can use it with all your current libraries (e.g. [Compojure](https://http-kit.github.io/server.html#routing)) and middleware.

- **High performance**: Using an event-driven architecture like Nginx, HTTP-kit is [very, very fast](https://github.com/taoensso/clojure-web-server-benchmarks). It comfortably handles tens of thousands of requests/sec on even midrange hardware. [Here](https://www.techempower.com/benchmarks/#section=data-r3) is another test about how it stacks up with others.

- **High concurrency**: It's not only fast, but [efficient](https://http-kit.github.io/600k-concurrent-connection-http-kit.html)! Each connection costs nothing but a few kB of memory. RAM usage grows O(n) with connections.

- **Clean, simple, small**: Written from the ground-up to be lean, the entire client/server is available as a single ~90kB JAR with zero dependencies and [~3k lines](https://http-kit.github.io/http-kit-clean-small.html) of (mostly Java) code.

- **Sync or async**: Synchronous is simple. Asynchronous is fast & flexible. With http-kit you get the best of both with a [simple API](https://http-kit.github.io/client.html) that lets you mix & match to best fit your use case.

- **WebSockets and Comet**: With great out-the-box support for both [WebSockets](https://http-kit.github.io/server.html#websocket) and efficient handling of [long-held HTTP requests](https://http-kit.github.io/server.html#async), realtime web applications are a breeze to write.

## Status

http-kit was created by [@shenfeng][], but is currently being maintained by its community.

A big thank-you to the [current contributors](../../graphs/contributors) for keeping the project going! **Additional contributors very welcome**: please ping me if you'd be interested in lending a hand.

\- [Peter Taoussanis][]

## Documentation

- [Full documentation][GitHub wiki] (**getting started** and more)
- Auto-generated API reference: [Codox][Codox docs], [clj-doc][clj-doc docs]

## License

Copyright &copy; 2012-2023 [Feng Shen][@shenfeng] and contributors.  
Licensed under [Apache 2.0](LICENSE.txt).

<!-- Common -->

[GitHub releases]: ../../releases
[GitHub issues]:   ../../issues
[GitHub wiki]:     ../../wiki

[Peter Taoussanis]: https://www.taoensso.com

<!-- Project -->

[Codox docs]:   https://http-kit.github.io/http-kit/
[clj-doc docs]: https://cljdoc.org/d/http-kit/http-kit/

[Clojars SVG]: https://img.shields.io/clojars/v/http-kit.svg
[Clojars URL]: https://clojars.org/http-kit

[Main tests SVG]:  https://github.com/http-kit/http-kit/actions/workflows/main-tests.yml/badge.svg
[Main tests URL]:  https://github.com/http-kit/http-kit/actions/workflows/main-tests.yml
[Graal tests SVG]: https://github.com/http-kit/http-kit/actions/workflows/graal-tests.yml/badge.svg
[Graal tests URL]: https://github.com/http-kit/http-kit/actions/workflows/graal-tests.yml

<!-- Unique -->

[@shenfeng]: https://github.com/shenfeng