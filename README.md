<span><img src="http-kit-logo.png" alt="http-kit" width="140"/></span>  
[**Documentation**](#documentation) | [Latest releases](#latest-releases) | [Slack channel][]

# http-kit

### Simple, high-performance event-driven HTTP client+server for Clojure

http-kit is a minimalist and efficient Ring-compatible HTTP client+server for Clojure.

It uses an event-driven architecture to support highly concurrent a/synchronous web applications, and features a simple unified API for WebSocket and HTTP long-polling/streaming.

## Latest release/s

- `2024-02-26` `2.8.0-RC1` (dev): [release info](../../releases/tag/v2.8.0-RC1)
- `2023-06-30` `2.7.0` (stable): [release info](../../releases/tag/v2.7.0)

[![Main tests][Main tests SVG]][Main tests URL]
[![Graal tests][Graal tests SVG]][Graal tests URL]

See [here][GitHub releases] for earlier releases.

## Why http-kit?

- **Ring compliant**: http-kit is a drop-in replacement for the standard Ring Jetty adapter. You can use it with all your current libraries and middleware.

- **High performance**: http-kit uses an event-driven architecture like nginx, and is **fast**. See [here](#performance) for benchmarks.

- **High concurrency**: http-kit is **efficient**. Its RAM usage is O(n), with only few kB used per connection. Tests have [shown](https://http-kit.github.io/600k-concurrent-connection-http-kit.html) http-kit happily serving >600k concurrent connections.

- **Clean, simple, small**: written from the ground-up to be **lean**, the entire http-kit client+server JAR is ~90kB with **zero dependencies** and ~3k total lines of code.

- **Sync or async**: synchronous is simple, asynchronous fast & flexible. With http-kit you get the best of both with a simple API that lets you mix & match to best fit your use case.

- **WebSockets**: realtime web apps are a breeze with http-kit, with great out-the-box support for both WebSockets and efficient HTTP long-polling.

## Performance

http-kit now includes an extensive single-system **benchmark suite** that can be easily customized and run in your own environment.

See [here](../../wiki/4-Benchmarking) for http-kit's **benchmark philosophy**, usage info, detailed results, etc.

Selected example results:

> **Important**: as with all benchmarks - please be skeptical and check the details for important context!

![chart-server-work-0](../../raw/master/benchmarks/charts/server-work-0.png)

![chart-client-https](../../raw/master/benchmarks/charts/client-https.png)

## Project status

http-kit was created by [@shenfeng][], but is currently being maintained by its community.

A big thank-you to the [current contributors](../../graphs/contributors) for keeping the project going! **Additional contributors very welcome**: please ping me if you'd be interested in lending a hand.

\- [Peter Taoussanis][]

## Documentation

- [Wiki][GitHub wiki] (getting started, usage, etc.)
- API reference: [cljdoc][cljdoc docs], [Codox][Codox docs]

## License

Copyright &copy; 2012-2024 [Feng Shen][@shenfeng] and contributors.  
Licensed under [Apache 2.0](LICENSE.txt).

<!-- Common -->

[GitHub releases]: ../../releases
[GitHub issues]:   ../../issues
[GitHub wiki]:     ../../wiki
[Slack channel]: https://www.taoensso.com/http-kit/slack

[Peter Taoussanis]: https://www.taoensso.com

<!-- Project -->

[Codox docs]:   https://http-kit.github.io/http-kit/
[cljdoc docs]: https://cljdoc.org/d/http-kit/http-kit/

[Clojars SVG]: https://img.shields.io/clojars/v/http-kit.svg
[Clojars URL]: https://clojars.org/http-kit

[Main tests SVG]:  https://github.com/http-kit/http-kit/actions/workflows/main-tests.yml/badge.svg
[Main tests URL]:  https://github.com/http-kit/http-kit/actions/workflows/main-tests.yml
[Graal tests SVG]: https://github.com/http-kit/http-kit/actions/workflows/graal-tests.yml/badge.svg
[Graal tests URL]: https://github.com/http-kit/http-kit/actions/workflows/graal-tests.yml

<!-- Unique -->

[@shenfeng]: https://github.com/shenfeng
