This project uses [**Break Versioning**](https://www.taoensso.com/break-versioning).

---

## `v2.7.0` (2023-06-30)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0)

Please **test carefully** and **report any issues**!

Identical to `v2.7.0-RC1` except for:

* cdfc5fb [fix] [client] [#524] Reliably close InputStream when data too large (@rublag)

### Changes since `v2.6.0` ⚠️

* [BREAK] [#528] [Client] Support for `:insecure?` flag is currently broken
* 6158351 [mod] [Client] [#501] [#502] Join multiple headers with "\n" rather than "," (@g23)

---

## `v2.7.0-RC1` (2023-05-30)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0-RC1)

Identical to `v2.7.0-beta3`.

---

## `v2.7.0-beta3` (2023-05-03)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0-beta3)

Identical to `v2.7.0-beta2` except for:

* 10501a5 [fix] [#520] Remove unintended dependency on `cider-nrepl` plugin (@harold)

---

## `v2.7.0-beta2` (2023-04-24)

> 📦 [Available on Clojars](https://clojars.org/http-kit/versions/2.7.0-beta2)

This is a **major pre-release** that includes many significant fixes and new features.  
Please test carefully and **report any issues**!

A big thanks to the many contributors 🙏

### Changes since `v2.6.0` ⚠️

* [BREAK] [#528] [Client] Support for `:insecure?` flag is currently broken
* 6158351 [mod] [Client] [#501] [#502] Join multiple headers with "\n" rather than "," (@g23)

### New since `v2.6.0`

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

### Fixes since `v2.6.0`

* 304c042 [fix] [Client] [#464] Retain dynamic client on client redirects
* 4ff7dba [fix] [Server] [#498] [#499] Don't send Content-Length header for status 1xx or 204 (@restenb)
* d6fa328 [fix] [Server] [#375] [EXPERIMENTAL] NB prevent unintentional re-use of channels (@osbert)
* 2feb510 [fix] [Client] [#446] [EXPERIMENTAL] Use host as key for keepalive conns (@luizhespanha)
* 48a4688 [fix] [Server] [#504] [#508] Add missing ^:deprecated meta for `with-channel`, et al.
* 5b742ed [fix] [Client] [#503] Properly close sockets when calling stop() on client (@benbramley)
* 7632f46 [fix] [Client] [#505] Prevent duplicate headers in HeaderMap, fix broken tests (@kipz)
* 550da73 [#493 #491] [Server] [fix] Send '100 Continue' response only once (@zgtm)

### Other improvements since `v2.6.0`

* c393759 [nop] [Tests] [#508] [#512] Move all tests from `with-channel` to `as-channel` (@kipz)
* e2d7103 [new] [Build] [#509] [#511] Add native-image test to CI (@borkdude)
* dff3bab [new] [Build] [#507] Add GitHub to build and run tests (@kipz)
* cf13651 [#495] [Server] [Docs] Improve error message of HTTP server loop error (@tweeks-reify)
* 88e5a04 [Housekeeping] [Server] Use const for Content-Length header
* deac8ee [Client] [Docs] Improve `client/request` docstring
* 9b04909 Update some dependencies

---

## v2.6.0 (2022-06-13)

```clojure
[http-kit "2.6.0"]
```

> Non-breaking maintenance release with some fixes and minor features
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

Identical to `v2.6.0-RC1`.

#### Changes since `v2.5.3`

* [#476] [Server] Optimization: change buildRequestMap to create a PersistentHashMap directly (@bsless)

#### New since `v2.5.3`

* [#471 #472] [Client] Add option to not automatically add Accept-Content header (@MarcoNicolodi)

#### Fixes since `v2.5.3`

* [#469 #489] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)
* [#475 #477] [Graal] Add --initialize-at-run-time to config to stop GRAAL builds failing (@askonomm)
* [#482 #483] [Client] Fix java version parsing for JDK 17 (@pmonks)
* [#401 #481] [Client] mark Request as connected when reusing keepalive (@xwang1498)

#### Everything since `v2.6.0-alpha1`

* [#469 #489] [Fix] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)

---

## v2.6.0-RC1 (2022 May 28)

```clojure
[http-kit "2.6.0-RC1"]
```

> Non-breaking maintenance release with some fixes and minor features
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

#### Changes since `v2.5.3`

* [#476] [Server] Optimization: change buildRequestMap to create a PersistentHashMap directly (@bsless)

#### New since `v2.5.3`

* [#471 #472] [Client] Add option to not automatically add Accept-Content header (@MarcoNicolodi)

#### Fixes since `v2.5.3`

* [#469 #489] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)
* [#475 #477] [Graal] Add --initialize-at-run-time to config to stop GRAAL builds failing (@askonomm)
* [#482 #483] [Client] Fix java version parsing for JDK 17 (@pmonks)
* [#401 #481] [Client] mark Request as connected when reusing keepalive (@xwang1498)

#### Everything since `v2.6.0-alpha1`

* [#469 #489] [Fix] [Client] Properly unrecycle req when kept-alive conn wasn't able to be reused (@xwang1498)

---

## v2.6.0-alpha1 (2021 Oct 16)

```clojure
[http-kit "2.6.0-alpha1"]
```

> Non-breaking maintenance release with some fixes and minor features
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

#### Changes since `v2.5.3`

* [#476] [Server] Optimization: change buildRequestMap to create a PersistentHashMap directly (@bsless)

#### New since `v2.5.3`

* [#471 #472] [Client] Add option to not automatically add Accept-Content header (@MarcoNicolodi)

#### Fixes since `v2.5.3`

* [#475 #477] [Graal] Add --initialize-at-run-time to config to stop GRAAL builds failing (@askonomm)
* [#482 #483] [Client] Fix java version parsing for JDK 17 (@pmonks)
* [#401 #481] [Client] mark Request as connected when reusing keepalive (@xwang1498)

---

## v2.5.3 (2021 Feb 21)

```clojure
[http-kit "2.5.3"]
```

> Non-breaking hotfix release.
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

#### Fixes since `v2.5.2`

* [#462 #437] Fix project.clj compiler option to support older JVMs (e.g. Java 8)

---

## v2.5.2 (2021 Feb 19)

```clojure
[http-kit "2.5.2"]
```

> Non-breaking hotfix release.
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

#### Fixes since `v2.5.1`

* [#457 #456] [Client] Fix race condition in clientContext initialization (@bsless)

---

## v2.5.1 (2021 Jan 14)

```clojure
[http-kit "2.5.1"]
```

> Non-breaking hotfix release.
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

#### Fixes since `v2.5.0`

* [#455] [Client] Fix Java version parsing used to set default client `hostname-verification?` option (@aiba)

---

## v2.5.0 (2020 Sep 18)

```clojure
[http-kit "2.5.0"]
```

> **Bumps minimum JVM version from 1.6 to 1.7**. _Should_ otherwise be non-breaking.  
> See [here](https://github.com/taoensso/encore#recommended-steps-after-any-significant-dependency-update) recommended steps when updating any Clojure/Script dependencies.

Identical to `v2.5.0-RC1`.

#### Changes since `v2.4.0`

* **BREAKING**: bump minimum JVM version from 1.6 to 1.7
* [#438 #439][Server] Stop using `sun.misc.Unsafe` (@kirked)

#### New since `v2.4.0`

* [#434][Client] GraalVM Native Image Compatibility: move SSL initialisation to constructor (@alekcz)
* [#433 #432 #129] [Server] Configurable server header (@barkanido)
* [#441][Server] Add 1-arity `server-stop!`

#### Fixes since `v2.4.0`

* [#429] Fix flaky server-status tests
* [Server][Tests] Fix lint issue with newer JDKs

---

# Earlier releases

See [here](https://github.com/http-kit/http-kit/releases) for earlier releases.