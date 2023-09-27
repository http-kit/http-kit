<span><img src="http-kit-logo.png" alt="http-kit" width="140"/></span>  
[**Documentation**](#documentation) | [Latest releases](#latest-releases) | [Get support][GitHub issues]

# http-kit

### Simple, high-performance event-driven HTTP client+server for Clojure

http-kit is a minimalist and efficient Ring-compatible HTTP client+server for Clojure.

It uses an event-driven architecture to support highly concurrent a/synchronous web applications, and features a simple unified API for WebSocket and HTTP long-polling/streaming.

## Latest release/s

- `2023-06-30` `2.7.0` (stable): [changes](../../releases/tag/v2.7.0)
- `2023-09-27` `2.8.0-beta1` (dev): [changes](../../releases/tag/v2.8.0-beta1)

[![Main tests][Main tests SVG]][Main tests URL]
[![Graal tests][Graal tests SVG]][Graal tests URL]

See [here][GitHub releases] for earlier releases.

## Why http-kit?

- **Ring compliant**: http-kit is a drop-in replacement for the standard Ring Jetty adapter. You can use it with all your current libraries and middleware.

- **High performance**: http-kit uses an event-driven architecture like nginx, and is **fast**. It comfortably [handles](https://github.com/taoensso/clojure-web-server-benchmarks/tree/master/results/legacy#legacy-results) tens of thousands of requests/sec on even low-end hardware.

- **High concurrency**: http-kit is **efficient**. Its RAM usage is O(n), with only few kB used per connection. Tests have [shown](https://http-kit.github.io/600k-concurrent-connection-http-kit.html) http-kit happily serving >600k concurrent connections.

- **Clean, simple, small**: written from the ground-up to be **lean**, the entire http-kit client+server JAR is ~90kB with zero dependencies and ~3k lines of code.

- **Sync or async**: synchronous is simple, asynchronous fast & flexible. With http-kit you get the best of both with a simple API that lets you mix & match to best fit your use case.

- **WebSockets and Comet**: realtime web apps are a breeze with http-kit, with great out-the-box support for both WebSockets and efficient HTTP long-polling.

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