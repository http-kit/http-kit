# HTTP Kit

### A high-performance event-driven HTTP client+server for Clojure
![master](https://github.com/alekcz/http-kit/workflows/master/badge.svg)  
[CHANGELOG][] | Current [semantic](http://semver.org/) version/s:

```clojure
[http-kit "2.3.0"]         ; Stable, published by contributors, see CHANGELOG for details
[http-kit "2.4.0-alpha6"]  ; Dev,    published by contributors, see CHANGELOG for details
[http-kit "2.1.19"]        ; Legacy, published by @shenfeng
```

See the [project website][] for documentation, examples, benchmarks, etc.

## Project status

http-kit's author ([@shenfeng][]) unfortunately hasn't had much time to maintain http-kit recently. To help out I'll be doing basic issue triage, accepting minor/obvious PRs, etc.

A big thank you to the **[current contributors](https://github.com/http-kit/http-kit/graphs/contributors)** for keeping the project going! **Additional contributors welcome**: please ping me if you'd be interested in lending a hand.

\- [@ptaoussanis][]

### Hack locally

Hacker friendly: zero dependencies, written from the ground-up with only ~3.5k lines of code (including java), clean and tidy.

```sh
# Modify as you want, unit tests back you up:
lein test

# May be useful (more info), see `server_test.clj`:
./scripts/start_test_server

# Some numbers on how fast can http-kit's client can run:
lein test :benchmark
```

### Enabling http-kit client SNI support

> Requires JVM >= 8, http-kit >= 2.4.0-alpha6.
> Common cause of: `javax.net.ssl.SSLHandshakeException: Received fatal alert: handshake_failure`

To retain backwards-compatibility with JVMs < 8, the http-kit client currently **does not have SNI support enabled by default**.

This default may be changed in a future breaking release. But in the meantime, manually enabling SNI support is easy:

```clojure
  (:require [org.httpkit.sni-client :as sni-client]) ; Needs Java >= 8

  ;; Change default client for your whole application:
  (alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

  ;; or temporarily change default client for a particular thread context:
  (binding [org.httpkit.client/*default-client* sni-client/default-client]
    <...>)
```

See `org.httpkit.client/*default-client*` docstring for more details.

### Contact & Contribution

Please use the [GitHub issues page](https://github.com/http-kit/http-kit/issues) for feature suggestions, bug reports, or general discussions. Current contributors are listed [here](https://github.com/http-kit/http-kit/graphs/contributors). The [project website][] is also on GitHub.

### License

Copyright &copy; 2012-2018 [@shenfeng][]. Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

[CHANGELOG]: https://github.com/http-kit/http-kit/releases
[@shenfeng]: https://github.com/shenfeng
[@ptaoussanis]: https://github.com/ptaoussanis
[project website]: https://http-kit.github.com
