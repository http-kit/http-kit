# HTTP Kit

## High-performance event-driven HTTP client/server for Clojure

See **[http-kit.org](http://http-kit.org)** for documentation, examples, benchmarks, etc.

Current [semantic](http://semver.org/) versions:

```clojure
[http-kit "2.1.19"]        ; Stable
[http-kit "2.2.21-alpha2"] ; Dev
```

## Library status

http-kit's author ([@shenfeng]) unfortunately hasn't had much time to maintain http-kit recently. In an attempt to help out I'll be doing issue triage, accepting minor/obvious PRs, etc. My own time's pretty limited so **contributors welcome**: looking for pull requests, feedback/ideas, and help dealing with GitHub issues, etc. Please contact me if you'd be interested in lending a hand. Thank you!

\- [@ptaoussanis]

### Hack locally

Hacker friendly: Zero dependency, written from ground-up with only ~3.5k lines of code (including java), clean and tidy.

```sh
# modify as you want, unit tests back you up
lein test

# may be useful. more info, see the code server_test.clj
./scripts/start_test_server

# some numbers about how fast can http-kit's client can run
lein test :benchmark
```

### Contact & Contribution

Please use the [GitHub issues page](https://github.com/http-kit/http-kit/issues) for feature suggestions, bug reports, or general discussions. Current contributors are listed [here](https://github.com/http-kit/http-kit/graphs/contributors). The http-kit.org website is also on GitHub [here](https://github.com/http-kit/http-kit.github.com).

### Change log

[history.md](https://github.com/http-kit/http-kit/blob/master/history.md)

### License

Copyright &copy; 2012-2015 [Feng Shen](http://shenfeng.me/). Distributed under the [Apache License Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

[@shenfeng]: https://github.com/shenfeng
[@ptaoussanis]: https://github.com/ptaoussanis
