This project uses [**Break Versioning**](https://www.taoensso.com/break-versioning).

---

# `v2.8.1` (2025-08-19)

- **Dependency**: [on Clojars](https://clojars.org/http-kit/versions/2.8.1)
- **Versioning**: [Break Versioning](https://www.taoensso.com/break-versioning)

This is a **stable hotfix release** to backport 2 commits from the v2.9 dev branch:

- \[new] \[server] [#588] [#589] Add `join-server` function (@hlship) \[a251a4c]
- \[fix] \[client] [#568] [#569] Fix performance regression (@bsless) \[a879b3d]

This should be a safe update for users of v2.8.0.

---

# `v2.9.0-beta1` (2025-04-15)

- **Dependency**: [on Clojars](https://clojars.org/http-kit/versions/2.9.0-beta1)
- **Versioning**: [Break Versioning](https://www.taoensso.com/break-versioning)

This is a **feature and maintenance** pre-release. It's expected to be stable but as always, please **test carefully and report any unexpected problems**, thank you! 🙏

See linked commits below for details, and big thanks to all contributors!

\- [Peter Taoussanis](https://www.taoensso.com)

## Since `v2.8.0` (2024-04-30)

* \[fix] [server] [#584] Reject invalid host header ports (@ianmuge) \[de33aed]
* \[fix] [server] [#578] [#579] Fix regression in #375 (@andersmurphy) \[76b869f]
* \[fix] [client] [#592] Fix number encoding in `MultipartEntity` (@GAumala) \[2baeab5]
* \[fix] [client] [#590] [#576] Fix warning during client tests (@kolstae) \[a8a5df8]
* \[fix] [client] [#574] [#575] Fix possible `java.util.zip.ZipException` for deflate encodings (@pieterbreed) \[42ad799]
* \[fix] [client] [#568] [#569] Fix performance regression (@bsless) \[3831982]
* \[new] [server] [#588] [#589] Add `join-server` function (@hlship) \[b31d588]
* \[new] [client] [#517] [#567] Add some Java methods to better enable instrumentation (@jefimm) \[082eb7f]
* \[doc] Mention `utils/new-worker` util \[1d173fe]
* \[doc] Extend production info section, link directly from README \[fd6b993]
* \[doc] [#570] [#572] Add updated WebSocket example to wiki (@jf) \[a0d39bf]

---

# `v2.8.0` (2024-04-30)

> **Dep/s**: http-kit is [on Clojars](https://clojars.org/http-kit/versions/2.8.0).  
> **Versioning**: http-kit uses [Break Versioning](https://www.taoensso.com/break-versioning).

This is a **major feature and maintenance** release. As always, please **test carefully and report any unexpected problems**, thank you! 🙏

Highlights include:

- Support for the latest Ring async and WebSocket APIs
- Performance improvements, incl. auto use of JVM 21+ virtual threads when available
- Numerous minor features, fixes, and other improvements

Please see linked commits below for details.

A huge thanks to the 10 contributors who helped create this release!

\- [Peter Taoussanis](https://www.taoensso.com)

## Changes since `v2.7.0` (2023-06-30)

* 6db3f0f [mod] Bump minimum Java version: 7->8
* If using **AOT/uberjar**, please ensure that you build with the **lowest Java version** that you'd like to support.

## Fixes since `v2.7.0` (2023-06-30)

* 2474302 [fix] [client] [#535] [#536] Fix handling of some bad ssl certificates (@jeffdik)
* b9f84d5 [fix] [client] [#523] Basic support for trailer section in chunked encoding responses
* b45725f [fix] [server] [#543] Migrate away from `SimpleDateFormat` to `java.time`, fixes native-image issue (@borkdude)
* 45a4b53 [fix] [server] [#537] Respond with `Connection: Close` when appropriate
* 126d5df [fix] [client] [#528] Possible fix for broken `insecure?` client option
* 9be19c0 [fix] [client] [#528] Re-enable insecure SSL client tests disabled for #513
* de3596a [fix] [server] [#539] [#540] Prevent race condition in `TimerService` (@weavejester)
* 99de95b [fix] [server] [#552] [#553] Bad arg order causing broken loggers config (@frwdrik)
* 2dcfa29 [fix] [server] [#546] Fix Jetty server SNI check in client tests (@weavejester)
* ed6833e [fix] [client] [#560] Attempted fix to allow SSL with IP host
* 48cb7fe [fix] [server] [#559] Add missing `Content-Type` header to last-resort error responses
* bfba515 [fix] [client] Ignore nil clients, even when they're delay-wrapped
* 8738140 [fix] [server] [#551] Unintentional code duplication (@slipset)
* 491e19c [fix] [tests] Flaky timing in CI
* e2ca731 [fix] Resolve Lein composite profile warning

## New since `v2.7.0` (2023-06-30)

* c91a752 [new] [server] [#546] Add support for Ring WebSocket API (@weavejester)
* 6652df8 [new] [server] [#394] [#538] Add support for Ring async handler arities (@weavejester)
* 47129af [new] [server] Refactor worker threading, use virtual threads by default on JVM 21+
* e38169b [new] [client] Refactor worker threading, use virtual threads by default on JVM 21+
* 741eed8 [new] [tests] Add new benchmark suite
* 2a74dbf [new] [tests] Add first benchmark results
* af5550f [new] [client] [#554] Support non-ASCII characters on multipart filenames (@davartens)
* 41940f3 [new] [client] [server] Add public worker constructors
* f267426 [new] [client] [server] `utils/new-worker` improvements
* 890de2d [new] [client] [#561] [#562] Add `:nested-param-style` option to client requests (@wevre)

## Other improvements since `v2.7.0` (2023-06-30)

* 5379f62 [new] [server] [#546] Also test without support for Ring WebSocket API
* 4813a17 [nop] [#530] [#531] Update dep: `http.async.client` (@NoahTheDuke)
* 9877bca [nop] [client] Don't submit tasks to closed pool
* 92fc3fe [wiki] Add client page from legacy website (@harold)
* 9b131e2 [wiki] Clean-up, update client docs
* Updated dependencies

## Everything since `v2.8.0-RC1` (2024-02-06)

* 890de2d [new] [client] [#561] [#562] Add `:nested-param-style` option to client requests (@wevre)

---

# `v2.8.0-RC1` (2024-02-26)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.8.0-RC1), this project uses [Break Versioning](https://www.taoensso.com/break-versioning).

This is a **major feature and maintenance** pre-release.  
Please **test carefully and report any unexpected problems**, thank you! 🙏

Highlights include:

- Support for the latest Ring async and WebSocket APIs
- Performance improvements, incl. auto use of JVM 21+ virtual threads when available
- Numerous minor features, fixes, and other improvements

Please see linked commits below for details.

A huge thanks to all contributors!

## Changes since `v2.7.x`

* 6db3f0f [mod] Bump minimum Java version: 7->8
* If using **AOT/uberjar**, please ensure that you build with the **lowest Java version** that you'd like to support.

## Fixes since `v2.7.x`

* 2474302 [fix] [client] [#535] [#536] Fix handling of some bad ssl certificates (@jeffdik)
* b9f84d5 [fix] [client] [#523] Basic support for trailer section in chunked encoding responses
* b45725f [fix] [server] [#543] Migrate away from `SimpleDateFormat` to `java.time`, fixes native-image issue (@borkdude)
* 45a4b53 [fix] [server] [#537] Respond with `Connection: Close` when appropriate
* 126d5df [fix] [client] [#528] Possible fix for broken `insecure?` client option
* 9be19c0 [fix] [client] [#528] Re-enable insecure SSL client tests disabled for #513
* de3596a [fix] [server] [#539] [#540] Prevent race condition in `TimerService` (@weavejester)
* 99de95b [fix] [server] [#552] [#553] Bad arg order causing broken loggers config (@frwdrik)
* 2dcfa29 [fix] [server] [#546] Fix Jetty server SNI check in client tests (@weavejester)
* ed6833e [fix] [client] [#560] Attempted fix to allow SSL with IP host
* 48cb7fe [fix] [server] [#559] Add missing `Content-Type` header to last-resort error responses
* bfba515 [fix] [client] Ignore nil clients, even when they're delay-wrapped
* 8738140 [fix] [server] [#551] Unintentional code duplication (@slipset)
* 491e19c [fix] [tests] Flaky timing in CI
* e2ca731 [fix] Resolve Lein composite profile warning

## New since `v2.7.x`

* c91a752 [new] [server] [#546] Add support for Ring WebSocket API (@weavejester)
* 6652df8 [new] [server] [#394] [#538] Add support for Ring async handler arities (@weavejester)
* 47129af [new] [server] Refactor worker threading, use virtual threads by default on JVM 21+
* e38169b [new] [client] Refactor worker threading, use virtual threads by default on JVM 21+
* 741eed8 [new] [tests] Add new benchmark suite
* 2a74dbf [new] [tests] Add first benchmark results
* af5550f [new] [client] [#554] Support non-ASCII characters on multipart filenames (@davartens)
* 41940f3 [new] [client] [server] Add public worker constructors
* f267426 [new] [client] [server] `utils/new-worker` improvements

## Other improvements since `v2.7.x`

* 5379f62 [new] [server] [#546] Also test without support for Ring WebSocket API
* 4813a17 [nop] [#530] [#531] Update dep: `http.async.client` (@NoahTheDuke)
* 9877bca [nop] [client] Don't submit tasks to closed pool
* 92fc3fe [wiki] Add client page from legacy website (@harold)
* 9b131e2 [wiki] Clean-up, update client docs
* Updated dependencies

---

# `v2.8.0-beta3` (2023-10-11)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.8.0-beta3), this project uses [Break Versioning](https://www.taoensso.com/break-versioning).

**Hotfix**: identical to `v2.8.0-beta2` but removes an unintended dependency on Cider. Thanks to @borkdude for the report!

# `v2.8.0-beta2` (2023-10-11)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.8.0-beta2), this project uses [Break Versioning](https://www.taoensso.com/break-versioning).

This is an early **maintenance and feature pre-release**.  
Please **test carefully and report any unexpected problems**, thank you! 🙏

## Fixes since `v2.8.0-beta1`

* de3596a [fix] [server] [#539] [#540] Prevent race condition in TimerService (@weavejester)
* 2474302 [fix] [client] [#535] [#536] Fix handling of some bad ssl certificates (@jeffdik)
* b45725f [fix] [server] [#543] Migrate away from SimpleDateFormat to java.time, fixes native-image issue (@borkdude)
* 45a4b53 [fix] [server] [#537] Respond with `Connection: Close` when appropriate

## New since `v2.8.0-beta1`

* 741eed8 [new] [tests] Add new benchmark suite
* 2a74dbf [new] [tests] Add first benchmark results
* 6652df8 [new] [server] [#394] [#538] Add support for Ring async handler arities (@weavejester)
* 41940f3 [new] [client] [server] Add public worker constructors
* f267426 [new] `utils/new-worker` improvements

## Other improvements since `v2.8.0-beta1`

* 7d49819 [nop] Bump Jetty dependency, fix broken tests
* 9877bca [nop] [client] Don't submit tasks to closed pool
* 92fc3fe [wiki] Add client page from legacy website (@harold)
* 9b131e2 [wiki] Clean-up, update client docs

---

# `v2.8.0-beta1` (2023-09-27)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.8.0-beta1), this project uses [Break Versioning](https://www.taoensso.com/break-versioning).

This is an early **maintenance and feature pre-release**.  
Please **test carefully and report any unexpected problems**, thank you! 🙏

The main improvement in this release is to make it easier to automatically get good performance from http-kit client + server. Virtual threads are now used by default for both client and server on Java 21+, otherwise the default worker pools are now automatically sized based on available processor count.

As before, you may still want to [customize](https://github.com/http-kit/http-kit/wiki/3-Server#custom-request-queues) your request queue and/or worker threading - but the changes here make it much easier to get started with reasonable defaults.

As always, feedback welcome! Cheers :-)

\- Peter Taoussanis

## Changes since `v2.7.x`

* 6db3f0f [mod] Bump minimum Java version: 7->8

## Fixes since `v2.7.x`

* b9f84d5 [fix] [client] [#523] Basic support for trailer section in chunked encoding responses
* 126d5df [fix] [client] [#528] Possible fix for broken `insecure?` client option
* 9be19c0 [fix] [client] [#528] Re-enable insecure SSL client tests disabled for #513

## New since `v2.7.x`

* 47129af [new] [server] Refactor worker threading, use virtual threads by default on JVM 21+
* e38169b [new] [client] Refactor worker threading, use virtual threads by default on JVM 21+

## Other improvements since `v2.7.x`

* 4813a17 [nop] [#530] [#531] Update dep: `http.async.client` (@NoahTheDuke)

---

# `v2.7.0` (2023-06-30)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0)

Please **test carefully** and **report any issues**!

Identical to `v2.7.0-RC1` except for:

* cdfc5fb [fix] [client] [#524] Reliably close InputStream when data too large (@rublag)

## Changes since `v2.6.0` ⚠️

* [BREAK] [#528] [Client] Support for `:insecure?` flag is currently broken
* 6158351 [mod] [Client] [#501] [#502] Join multiple headers with "\n" rather than "," (@g23)

---

# `v2.7.0-RC1` (2023-05-30)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0-RC1)

Identical to `v2.7.0-beta3`.

---

# `v2.7.0-beta3` (2023-05-03)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0-beta3)

Identical to `v2.7.0-beta2` except for:

* 10501a5 [fix] [#520] Remove unintended dependency on `cider-nrepl` plugin (@harold)

---

# `v2.7.0-beta2` (2023-04-24)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0-beta2)

This is a **major pre-release** that includes many significant fixes and new features.  
Please test carefully and **report any issues**!

A big thanks to the many contributors 🙏

## Changes since `v2.6.0` ⚠️

* [BREAK] [#528] [Client] Support for `:insecure?` flag is currently broken
* 6158351 [mod] [Client] [#501] [#502] Join multiple headers with "\n" rather than "," (@g23)

## New since `v2.6.0`

* ed1cb8e [nop] [Docs] Introduced a new [community docs wiki](https://github.com/http-kit/http-kit/wiki)
* e5c8caa [new] [Client] [#393 #513] Use SNI client by default for Java >= 8
* 02a3739 [new] [Server] [Client] [#510] [#461] Support custom address & channel providers, enable Unix Domain Sockets (@kipz)
* 17eacca [new] [Client] Support dereffable `:client` vals
* 7cba2db [new] [Server] [#504] Add temporary clj-kondo support for `with-channel` (@figurantpp)
* eafdfcd [#224 #451] [Client] [New] Add basic multipart/mixed support (@vtrbtf)
* 4dd4ee5 [#400 #490] [Client] [New] Add `:as :raw-byte-array` coercion for babashka use case (@xfthhxk)
* 13e38b8 [#485] [Client] [New] Add HTTP LIST method (@greglook)
* 727b4f1 [#484] [Server] [New] Add `:start-time` initial timestamp to requests (@niquola)
* 754fe88 [#479] [Server] [New] Add `org.http-kit.memmap-file-threshold` JVM property (@ikappaki)

## Fixes since `v2.6.0`

* 304c042 [fix] [Client] [#464] Retain dynamic client on client redirects
* 4ff7dba [fix] [Server] [#498] [#499] Don't send Content-Length header for status 1xx or 204 (@restenb)
* d6fa328 [fix] [Server] [#375] [EXPERIMENTAL] NB prevent unintentional re-use of channels (@osbert)
* 2feb510 [fix] [Client] [#446] [EXPERIMENTAL] Use host as key for keepalive conns (@luizhespanha)
* 48a4688 [fix] [Server] [#504] [#508] Add missing ^:deprecated meta for `with-channel`, et al.
* 5b742ed [fix] [Client] [#503] Properly close sockets when calling stop() on client (@benbramley)
* 7632f46 [fix] [Client] [#505] Prevent duplicate headers in HeaderMap, fix broken tests (@kipz)
* 550da73 [#493 #491] [Server] [fix] Send '100 Continue' response only once (@zgtm)

## Other improvements since `v2.6.0`

* c393759 [nop] [Tests] [#508] [#512] Move all tests from `with-channel` to `as-channel` (@kipz)
* e2d7103 [new] [Build] [#509] [#511] Add native-image test to CI (@borkdude)
* dff3bab [new] [Build] [#507] Add GitHub to build and run tests (@kipz)
* cf13651 [#495] [Server] [Docs] Improve error message of HTTP server loop error (@tweeks-reify)
* 88e5a04 [Housekeeping] [Server] Use const for Content-Length header
* deac8ee [Client] [Docs] Improve `client/request` docstring
* 9b04909 Update some dependencies

---

# v2.6.0 (2022-06-13)

```clojure
[http-kit "2.6.0"]
```

> Non-breaking maintenance release with some fixes and minor features
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

Identical to `v2.6.0-RC1`.

## Changes since `v2.5.3`

* [#476] [Server] Optimization: change buildRequestMap to create a PersistentHashMap directly (@bsless)

## New since `v2.5.3`

* [#471 #472] [Client] Add option to not automatically add Accept-Content header (@MarcoNicolodi)

## Fixes since `v2.5.3`

* [#469 #489] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)
* [#475 #477] [Graal] Add --initialize-at-run-time to config to stop GRAAL builds failing (@askonomm)
* [#482 #483] [Client] Fix java version parsing for JDK 17 (@pmonks)
* [#401 #481] [Client] mark Request as connected when reusing keepalive (@xwang1498)

## Everything since `v2.6.0-alpha1`

* [#469 #489] [Fix] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)

---

# v2.6.0-RC1 (2022 May 28)

```clojure
[http-kit "2.6.0-RC1"]
```

> Non-breaking maintenance release with some fixes and minor features
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

## Changes since `v2.5.3`

* [#476] [Server] Optimization: change buildRequestMap to create a PersistentHashMap directly (@bsless)

## New since `v2.5.3`

* [#471 #472] [Client] Add option to not automatically add Accept-Content header (@MarcoNicolodi)

## Fixes since `v2.5.3`

* [#469 #489] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)
* [#475 #477] [Graal] Add --initialize-at-run-time to config to stop GRAAL builds failing (@askonomm)
* [#482 #483] [Client] Fix java version parsing for JDK 17 (@pmonks)
* [#401 #481] [Client] mark Request as connected when reusing keepalive (@xwang1498)

## Everything since `v2.6.0-alpha1`

* [#469 #489] [Fix] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)

---

# v2.6.0-alpha1 (2021 Oct 16)

```clojure
[http-kit "2.6.0-alpha1"]
```

> Non-breaking maintenance release with some fixes and minor features
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

## Changes since `v2.5.3`

* [#476] [Server] Optimization: change buildRequestMap to create a PersistentHashMap directly (@bsless)

## New since `v2.5.3`

* [#471 #472] [Client] Add option to not automatically add Accept-Content header (@MarcoNicolodi)

## Fixes since `v2.5.3`

* [#475 #477] [Graal] Add --initialize-at-run-time to config to stop GRAAL builds failing (@askonomm)
* [#482 #483] [Client] Fix java version parsing for JDK 17 (@pmonks)
* [#401 #481] [Client] mark Request as connected when reusing keepalive (@xwang1498)

---

# v2.5.3 (2021 Feb 21)

```clojure
[http-kit "2.5.3"]
```

> Non-breaking hotfix release.
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

## Fixes since `v2.5.2`

* [#462 #437] Fix project.clj compiler option to support older JVMs (e.g. Java 8)

---

# v2.5.2 (2021 Feb 19)

```clojure
[http-kit "2.5.2"]
```

> Non-breaking hotfix release.
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

## Fixes since `v2.5.1`

* [#457 #456] [Client] Fix race condition in clientContext initialization (@bsless)

---

# v2.5.1 (2021 Jan 14)

```clojure
[http-kit "2.5.1"]
```

> Non-breaking hotfix release.
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

## Fixes since `v2.5.0`

* [#455] [Client] Fix Java version parsing used to set default client `hostname-verification?` option (@aiba)

---

# v2.5.0 (2020 Sep 18)

```clojure
[http-kit "2.5.0"]
```

> **Bumps minimum JVM version from 1.6 to 1.7**. _Should_ otherwise be non-breaking.  
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

Identical to `v2.5.0-RC1`.

## Changes since `v2.4.0`

* **BREAKING**: bump minimum JVM version from 1.6 to 1.7
* [#438 #439][Server] Stop using `sun.misc.Unsafe` (@kirked)

## New since `v2.4.0`

* [#434][Client] GraalVM Native Image Compatibility: move SSL initialisation to constructor (@alekcz)
* [#433 #432 #129] [Server] Configurable server header (@barkanido)
* [#441][Server] Add 1-arity `server-stop!`

## Fixes since `v2.4.0`

* [#429] Fix flaky server-status tests
* [Server][Tests] Fix lint issue with newer JDKs

---

# Earlier releases

See [here](https://github.com/http-kit/http-kit/releases) for earlier releases.
