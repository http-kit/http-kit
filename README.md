<img src="http-kit-logo.png" alt="http-kit" width="140"/>

# http-kit

#### Simple, high-performance event-driven HTTP client+server for Clojure

### Latest release

- 2023-06-30: `2.7.0` - [release notes](https://github.com/http-kit/http-kit/releases/tag/v2.7.0) | [Clojars](https://clojars.org/http-kit/versions/2.7.0)

[![Main tests](https://github.com/http-kit/http-kit/actions/workflows/main-tests.yml/badge.svg)](https://github.com/http-kit/http-kit/actions/workflows/main-tests.yml) [![Graal tests](https://github.com/http-kit/http-kit/actions/workflows/graal-tests.yml/badge.svg)](https://github.com/http-kit/http-kit/actions/workflows/graal-tests.yml)

### Resources
1. [Wiki][wiki] - **community docs** (👈 start here)
1. [Release info][] - releases and changes
1. [API docs][] - auto-generated API docs
1. [GitHub issues][] - for support requests, contributions, etc.

### Status

http-kit was created by [@shenfeng][], but is currently being maintained by its community.

A big thank-you to the [current contributors](https://github.com/http-kit/http-kit/graphs/contributors) for keeping the project going! **Additional contributors very welcome**: please ping me if you'd be interested in lending a hand.

\- [Peter Taoussanis][]

### Features

> Links below point to the [legacy website](https://http-kit.github.io), which is currently unmaintained and may be outdated. A community effort is [now underway][wiki] to slowly transition away from the legacy website.

- **Ring compliant**: http-kit is an [(almost)](http://http-kit.github.io/migration.html) drop-in replacement for the standard Ring Jetty adapter. So you can use it with all your current libraries (e.g. [Compojure](http://http-kit.github.io/server.html#routing)) and middleware.

- **High performance**: Using an event-driven architecture like Nginx, HTTP-kit is [very, very fast](https://github.com/ptaoussanis/clojure-web-server-benchmarks). It comfortably handles tens of thousands of requests/sec on even midrange hardware. [Here](http://www.techempower.com/benchmarks/#section=data-r3) is another test about how it stacks up with others.

- **High concurrency**: It's not only fast, but [efficient](http://http-kit.github.io/600k-concurrent-connection-http-kit.html)! Each connection costs nothing but a few kB of memory. RAM usage grows O(n) with connections.

- **Clean, simple, small**: Written from the ground-up to be lean, the entire client/server is available as a single ~90kB JAR with zero dependencies and [~3k lines](http://http-kit.github.io/http-kit-clean-small.html) of (mostly Java) code.

- **Sync or async**: Synchronous is simple. Asynchronous is fast & flexible. With http-kit you get the best of both with a [simple API](http://http-kit.github.io/client.html) that lets you mix & match to best fit your use case.

- **WebSockets and Comet**: With great out-the-box support for both [WebSockets](http://http-kit.github.io/server.html#websocket) and efficient handling of [long-held HTTP requests](http://http-kit.github.io/server.html#async), realtime web applications are a breeze to write.

## License

Copyright &copy; 2012-2023 [@shenfeng][] and contributors, licensed under [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

<!--- Common links -->
[wiki]: ../../wiki
[Release info]: ../../releases
[GitHub issues]: ../../issues
[Peter Taoussanis]: https://www.taoensso.com

<!--- Repo links -->
[API docs]: http://http-kit.github.io/http-kit/
[@shenfeng]: https://github.com/shenfeng
