(ns org.httpkit.benchmark
  "New benchmark suite for http-kit.

  The approach taken here is far from perfect, but should hopefully
  be ~good enough for a single-system benchmark to:

    1. Provide basic baseline for regression/optimization testing
    2. Provide basic ballpark estimates of expected real-world performance
    3. While keeping benchmarks easy to run and maintain

  Improvements & corrections very welcome!

  Note on `wrk` vs `wrk2`:
    While wrk2 has traditionally offered better protection against \"coordinated
    omission\", I've chosen to go with base wrk here for the following reasons:

      1. wrk  is actively maintained, and wrk2 is not.
      2. wrk  is easier to install (e.g. is available via most package managers).
      3. wrk2 is currently broken on some systems, including Apple silicon Macs.
      4. Modern wrk has introduced decent mitigations against coordinated
         omission that seem to work well enough for our needs."

  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [clojure.string               :as str]
   [org.httpkit.benchmark.utils  :as u]
   [org.httpkit.benchmark.client :as client]
   [org.httpkit.benchmark.server :as server]))

(comment (set! *unchecked-math* false))
(comment (remove-ns 'org.httpkit.benchmark))

;;;; profiles

(def profiles-help
  "Each profile specifies >=1 dynamic specs (opts sets) to bench.

  Dynamic: opts may depend on system capabilities (esp. core count).
  See this var's code/value for details.

  Current profiles are experimental: names and specs both subject to change!"
  nil)

(def client-profiles client/profiles)
(def server-profiles server/profiles)

;;;;

(defn bench-all
  "Runs client & server benchmarks."
  [{:as   bench-opts
    :keys [metadata profile dry-run?]
    client-opts :client
    server-opts :server}]

  (let [t0 (System/currentTimeMillis)
        system-info (u/get-system-info)

        [client-opts server-opts]
        (let [common-defaults {:port (u/rand-free-port) :profile :quick}
              common-pre      (u/or-defaults (select-keys bench-opts [:profile :dry-run?]) common-defaults)
              common-post     {:metadata metadata :system-info system-info}]

          [(merge common-pre client-opts common-post)
           (merge common-pre server-opts common-post)])]

    (binding [u/*dry-run?* dry-run?]
      (u/log "***************************")
      (u/log "[bench-suite] Starting" u/newline (:as-str system-info))
      (reset! u/last-errors_ {})
      (let [[client-file client-result] (client/bench-by-profile client-opts)
            [server-file server-result] (server/bench-by-profile server-opts)

            _ (u/log "[bench-suite] Finished in " (u/secs-since t0) " seconds")
            output {:client client-result :server server-result}]

        (when-not dry-run?
          (let [{:keys [instant]} system-info
                edn-file (u/get-file instant "all.edn")]

            (spit edn-file (pr-str output))
            (when-let [f client-file] (u/log "[bench-suite] Saved client csv output to: " f))
            (when-let [f server-file] (u/log "[bench-suite] Saved server csv output to: " f))
            (let      [f edn-file]    (u/log "[bench-suite] Saved all    edn output to: " f))))

        (when-let [last-errors (not-empty @u/last-errors_)]
          (u/log "[bench-suite] *** WARNING: there were errors! ***")
          (u/log "[bench-suite] Last errors by key:")
          (doseq [[k v] last-errors] (u/log "Key: " k u/newline v))
          (u/log))

        (u/log "[bench-suite] All done!")
        (u/log "***************************")
        output))))

(comment
  (let [profile :quick
        md {:author "@ptaoussanis" :description "Debug run on 2020 Apple MBP M1"}]
    (do                          (bench-all {:metadata md :profile profile :dry-run? true}))
    (def   ab1 (abortable (fn [] (bench-all {:metadata md :profile profile}))))
    (deref ab1 20000 nil))
  (ab1) ; Abort
  )

;;;;

(defn -main
  "Called via `benchmarks` script, Rake `bench` task, etc."
  [& args]
  (u/log "[bench-suite] Reading first arg as edn bench opts")
  (try
    (let [bench-opts (clojure.edn/read-string (first args))]
      (u/log "[bench-suite] Using bench opts:")
      (u/log "  " bench-opts u/newline)
      (bench-all  bench-opts)
      (u/log "[bench-suite] Exiting (code: 0)")
      (System/exit 0))

    (catch Throwable t
      (u/log "[bench-suite] Unexpected fatal error ("  t "):" u/newline (u/ex->str t) u/newline)
      (u/log "[bench-suite] Exiting (code: 1)")
      (System/exit 1))))
