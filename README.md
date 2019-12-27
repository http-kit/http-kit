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

### Issue [SNI support to be enabled by default?](https://github.com/http-kit/http-kit/issues/393)

There is still an open discussion on how to address the fix that will not
break users' setup; the [most accepted workaround](https://github.com/http-kit/http-kit/issues/393#issuecomment-563820823) is to provide a custom
ssl-configurer when creating a client, here is the suggested code example:

``` clojure
(ns foo.core
  (:require [org.httpkit.client :as http-client])
  (:import java.net.URI
           [javax.net.ssl SNIHostName SSLEngine]))

(defn ssl-configurer [^SSLEngine eng, ^URI uri]
  (let [host-name (SNIHostName. (.getHost uri))
        params (doto (.getSSLParameters eng)
                 (.setServerNames [host-name]))]
    (doto eng
      (.setUseClientMode true) ;; required for JDK12/13 but not for JDK1.8
      (.setSSLParameters params))))

(comment

  (def client (http-client/make-client {:ssl-configurer ssl-configurer}))

  @(http-client/request {:method :get
                         :url "https://www.google.com/"
                         :client client})
  )
```

Since this is very common, http-kit provides a special namespace with the
workaround already in place; it will still be possible to rollout a custom
solution if needed.

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
