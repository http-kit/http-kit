# Getting started

http-kit now includes an extensive and easy-to-use single-system **benchmark suite** with tests for both the client and server.

To run these on your system:

1. Ensure that you have [wrk](https://github.com/wg/wrk) installed.
2. Clone the http-kit repo locally.
3. Run `rake bench` or `rake bench['{<edn-opts>}']` from the repo's root dir.

Detailed results will be automatically exported to `.edn` and `.csv` files.

# Configuration

Use `rake bench['{<edn-opts>}']` to provide custom benchmark options.

Some example calls:

```bash
rake bench['{:profile :quick :client {:skip? true}}']

rake bench['{:profile :quick :server {:total-runtime "2m"} :client {:n-reqs 2500} :metadata {:author "@your-username" :description "2020 MBP M1"}}']

rake bench['{:profile :full :server {:total-runtime "11h"} :client {:n-reqs 50000} :metadata {:author "@your-username" :description "2020 MBP M1"}}']
```

Benchmarks can also easily be run and configured from the REPL.

See the [benchmark namespace](../blob/master/test/org/httpkit/benchmark.clj) for more info on options and advanced usage.

# Philosophy

> **Important**, please read!

A good benchmark is clear, accurate, and useful for some well-defined purpose. Designing good benchmarks can be surprisingly difficult, especially **general-purpose comparative** benchmarks.

And unfortunately the **accurate interpretation** of results can often be just as difficult.

It's tempting to want to conclude that "X is faster/cheaper/better than Y", but real-world trade-offs mean that these kind of simplistic statements rarely make sense without a good bit of context.

The approach taken with the benchmarks here is to emphasize testing across **a wide range of parameters** (currently 480 combinations in total).

All the results are available in detailed [CSV files](../tree/master/benchmarks) and [Google sheets](https://docs.google.com/spreadsheets/d/1RLNkhlgtcleoAS1-HBuu8_Ait0pqFBF3MzSUPfZfyvI/edit?usp=sharing), making it easy to **pivot/filter** and compare the results **most applicable** to **your particular scenario and goals**.

Please interpret these (and all benchmark!) results **critically**, considering precisely:

- **What** is being tested
- **How** it's being tested
- What implications **may/not** be reasonable and meaningfully applicable to your own scenario and goals

As an example, please note that **all** results here are **single-system** benchmarks. I.e. the software **being** benched and the software **doing** the benching are both running on the same system and sharing resources.

This makes benching easy, and should be sufficient for our purposes - but be aware that this will of course affect measurements.

Additions and improvements to the bench suite are very welcome, as are results from different systems and/or with different parameters.

\- [Peter Taoussanis](https://www.taoensso.com)

# Results

See below for a selection of charts that necessarily choose **a very narrow and somewhat arbitrary** view on the underlying data.

As mentioned in the [philosophy](#philosophy) section, please carefully consider the details before coming to any conclusions. **Different parameters can yield different results!**

For a broader view and chart details, see the underlying data:

- Raw CSV files: [here](../tree/master/benchmarks)
- Google Sheet: [here](https://docs.google.com/spreadsheets/d/1RLNkhlgtcleoAS1-HBuu8_Ait0pqFBF3MzSUPfZfyvI/edit?usp=sharing) (includes example pivots that can be adjusted to your needs)

## Server

#### Chart 1: server with no-cost handler

An artificial test to roughly estimate the upper-bound of web server performance when Ring request handlers aren't actually doing any work.

Performance here tends to be limited by web server I/O and performance.

![chart-server-work-0](../raw/master/benchmarks/charts/server-work-0.png)

#### Chart 2: server with 40 msec handler

As `Chart 1`, but randomly ~simulates 10-70 msecs of handler work for each request to try approximate more realistic behaviour.

**Important**: real "work" can consist of I/O, CPU load, waiting for a DB or other service, network requests, etc. Usefully simulating "work" quickly becomes complex and case-specific. These benchmarks mix configurable sleeping and hot-looping as a rough approximation of some common cases.

It should also be noted that there's an inherent conflict: do we we want to measure _web-server performance_ or _application performance_? As application-level work increases, it'll tend to quickly dominate total response time, overwhelming many differences in web-server performance.

Of course this also illuminates an important point: if you have a highly dynamic and costly application (as many Clojure applications are), your web-server performance **might not actually matter much**. You'll need to consider the details in your case.

![chart-server-work-40](../raw/master/benchmarks/charts/server-work-40.png)

## Client

#### Chart 3: client HTTPs requests

![chart-client-https](../raw/master/benchmarks/charts/client-https.png)

#### Chart 4: client HTTP requests

![chart-client-http](../raw/master/benchmarks/charts/client-http.png)