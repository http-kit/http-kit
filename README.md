# HTTP Kit

### A high-performance event-driven HTTP client+server for Clojure

[CHANGELOG][] | Current [semantic](http://semver.org/) version/s:

```clojure
[http-kit "2.3.0"]         ; Stable, published by contributors, see CHANGELOG for details
[http-kit "2.4.0-alpha3"]  ; Dev,    published by contributors, see CHANGELOG for details
[http-kit "2.1.19"]        ; Legacy, published by @shenfeng
```

See [http-kit.org](http://http-kit.org) for documentation, examples, benchmarks, etc. (no longer maintained, some examples may contain minor bugs).

## Project status

http-kit's author ([@shenfeng][]) unfortunately hasn't had much time to maintain http-kit recently. To help out I'll be doing basic issue triage, accepting minor/obvious PRs, etc.

A big thank you to the **[current contributors](https://github.com/http-kit/http-kit/graphs/contributors)** for keeping the project going! **Additional contributors welcome**: please ping me if you'd be interested in lending a hand.

\- [@ptaoussanis][]

### Debugging SSLHandshakeException [SNI support to be enabled by default?](http)s://github.com/http-kit/http-kit/issues/393)

If you just use http-kit client and you get an exception:

`javax.net.ssl.SSLHandshakeException: Received fatal alert: handshake_failure`

it means that you are trying to contact a website which uses SNI during
the SSL handshake but the default client does not handle SNI properly and
this generates the handshake exception.

Since this is a common source of confusion, a new namespace have been
introduced (`org.httpkit.sni-client`) which provides a pre configured
client with a :sni-configurer in the var `default-sni-client`.

The new default-sni-client can be used as is (just remenber to deref it),
passing it to `request` function via the :client option, or can be set as
the default one for the whole application:

``` clojure
(alter-var-root #'org.httpkit.client/*default-client* default-sni-client)
```

Please refer to `org.httpkit.sni-client` and `org.httpkit.client`
namespaces for further details.

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

### Contact & Contribution

Please use the [GitHub issues page](https://github.com/http-kit/http-kit/issues) for feature suggestions, bug reports, or general discussions. Current contributors are listed [here](https://github.com/http-kit/http-kit/graphs/contributors). The http-kit.org website is also on GitHub [here](https://github.com/http-kit/http-kit.github.com).

### License

Copyright &copy; 2012-2018 [Feng Shen](http://shenfeng.me/). Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

[CHANGELOG]: https://github.com/http-kit/http-kit/releases
[@shenfeng]: https://github.com/shenfeng
[@ptaoussanis]: https://github.com/ptaoussanis
