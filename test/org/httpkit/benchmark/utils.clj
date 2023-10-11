(ns org.httpkit.benchmark.utils
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:refer-clojure :exclude [newline])
  (:require
   [clojure.string     :as str]
   [clojure.stacktrace :as st]
   [clojure.java.io    :as jio]
   [org.httpkit.utils  :as utils]))

(comment (remove-ns 'org.httpkit.benchmark.utils))

;;;; Consts

(def ^:dynamic *verbose-logging?* false)
(def ^:const dep-http-kit "http-kit v2.8.0-beta2")
(def ^:const dep-jetty    "ring-jetty-adapter v1.10.0 (Jetty 9.4.51.v20230217)")
(def ^:const dep-clj-http "clj-http v3.12.3")

;;;; Misc utils

(do
  (def  num-cores  (.availableProcessors (Runtime/getRuntime)))
  (def  properties (into {} (System/getProperties)))
  (def  env        (into {} (System/getenv)))
  (def  newline    (properties "line.separator"))
  (defn props [& keys] (str/join " " (map properties keys))))

(comment (props "os.name" "os.version"))

(do
  (defn round0   ^long [n]            (Math/round    (double n)))
  (defn round1 ^double [n] (/ (double (Math/round (* (double n) 1e1))) 1e1))
  (defn round2 ^double [n] (/ (double (Math/round (* (double n) 1e2))) 1e2))
  (defn round4 ^double [n] (/ (double (Math/round (* (double n) 1e4))) 1e4))
  (defn perc ^long [n divisor] (Math/round (* (/ (double n) (double divisor)) 100.0))))

(let [dfs (java.text.DecimalFormatSymbols. java.util.Locale/US)
      df  (java.text.DecimalFormat. "0.0000" dfs)]
  (defn format-round4 [n] (when n (.format df (double n)))))

(comment (format-round4 2.99999))

(defn format-instant [^java.time.Instant inst]
  (let [dtf
        (.withZone
          (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss")
          java.time.ZoneOffset/UTC)]
    (.format dtf inst)))

(comment (format-instant (java.time.Instant/now)))

(defn secs-since [^long t0]
  (let [t1 (System/currentTimeMillis)
        msecs-elapsed  (- (System/currentTimeMillis) t0)]
    (round1 (/ msecs-elapsed 1000.0))))

(comment (secs-since (- (System/currentTimeMillis) 2500)))

(def ^:dynamic *dry-run?* false)
(defn log [& xs] (when-not *dry-run?* (println (apply str xs))))

(defn ex->str [t] (with-out-str (st/print-stack-trace t)))
(comment (println (ex->str (ex-info "foo" {:a :A}))))

(defonce last-errors_ (atom {}))
(defn error! [key err] (swap! last-errors_ assoc key err))

(defn wait [^long secs]
  (log "Sleeping " secs " seconds")
  (Thread/sleep (* secs 1000)))

(defn join->csv [xs]
  (let [first?_ (atom true)]
    (reduce
      (fn rf [acc x]
        (cond
          (vector? x) (reduce rf acc x)
          :else
          (if (compare-and-set! first?_ true false)
            (str acc     x)
            (str acc "," x))))
      "" xs)))

(comment (join->csv [["a" "b" nil ["c" "d" ["e"]]] nil "f"]))

(defn quoted [x] (str \" x \"))

(defn standard-csv-rows
  ([] ; Headers
   [["Meta.author" "Meta.description" "Meta.comments"]
    ["Timestamp" "System.cores" "System.memory (GiB)" "Clojure.version" "OS" "Java.version" "JVM.version" "JVM.memory (GiB)"]
    ["Resp.length (bytes)" "Resp.min-sleep (msecs)" "Resp.max-sleep (msecs)" "Resp.min-work (msecs)" "Resp.max-work (msecs)"]])

  ([{:keys [nat-idx metadata system-info server-opts]}]
   [(let [{:keys [author description comments]} metadata]
      [(quoted author) (quoted description) (quoted comments)])

    (let [{:keys [instant sys-cores sys-mem clojure os java jvm jvm-mem]} system-info]
      [(when instant (format-instant instant)) sys-cores sys-mem clojure os java jvm jvm-mem])

    (let [{:keys [resp-len resp-work]} server-opts
          {:keys [sleep hot]} resp-work
          [min-sleep max-sleep] sleep
          [min-hot   max-hot]   hot]
      [resp-len min-sleep max-sleep min-hot max-hot])]))

(comment [(standard-csv-rows) (standard-csv-rows {})])

(defn time-str->msecs
  "Converts a wrk-style time string (\"2s\" \"2m\" \"2h\") to milliseconds."
  ^long [s]
  (round0
    (or
      (when-let [[_ n] (re-matches #"(\d+(\.\d+)?)s$" s)] (* (parse-double n) 1e3))
      (when-let [[_ n] (re-matches #"(\d+(\.\d+)?)m$" s)] (* (parse-double n) 6e4))
      (when-let [[_ n] (re-matches #"(\d+(\.\d+)?)h$" s)] (* (parse-double n) 3.6e6))
      0.0)))

(comment (mapv time-str->msecs ["1s" "1m" "1h" "1.5h"]))

(defn msecs->secs ^long   [n-msecs] (round0 (/ (double n-msecs) 1e3)))
(defn msecs->mins ^double [n-msecs] (round1 (/ (double n-msecs) 6e4)))

(comment (msecs->mins 30000))

(defn clamp [n-min n-max n] (max (min n n-max) n-min))
(comment (clamp 2 5 8))

(defn get-system-info []
  (let [^com.sun.management.OperatingSystemMXBean os
        (java.lang.management.ManagementFactory/getOperatingSystemMXBean)

        bytes->gib (fn [bytes] (round1 (/ (long bytes) (Math/pow 1024.0 3))))

        {:keys [instant sys-cores sys-mem clojure os java jvm jvm-mem] :as info-map}
        {:instant   (java.time.Instant/now)
         :sys-cores num-cores
         :sys-mem   (bytes->gib (.getTotalPhysicalMemorySize os))
         :clojure   (clojure-version)
         :os        (props "os.name" "os.version")
         :java      (props "java.version")
         :jvm       (props "java.vm.name" "java.vm.version")
         :jvm-mem   (bytes->gib (.maxMemory (Runtime/getRuntime)))}]

    (assoc info-map :as-str
      (str
        "  Timestamp: " (format-instant instant) newline
        "  Sys cores: " num-cores      newline
        "    Sys mem: " sys-mem " GiB" newline
        "    Clojure: " clojure        newline
        "         OS: " os             newline
        "       Java: " java           newline
        "        JVM: " jvm            newline
        "    JVM mem: " jvm-mem " GiB"
        newline))))

(comment (println (:as-str (get-system-info))))

(def have-virtual-threads? utils/have-virtual-threads?)

(defn or-defaults [opts defaults]
  (merge-with (fn [d o] (or o d)) defaults opts)  )

(comment (or-defaults {:a nil :b :B1} {:a :A2 :b :B2}))

;;;; Shell stuff

(defonce ^:private running-shell-procs_ (atom #{}))
(defn         kill-running-shell-procs! []
  (doseq [^Process proc @running-shell-procs_]
    (.destroyForcibly proc)))

(defonce ^:private shell-shutdown-hook
  (.addShutdownHook (Runtime/getRuntime)
    (Thread. (fn [] (kill-running-shell-procs!)))))

(defn shell
  "Executes given shell command and returns a job that you can:
    - Deref  to get ?{:keys [exit out err]}
    - Invoke to get underlying `Process`"

  ([timeout-msecs args]
   (deref (shell args) timeout-msecs
     {:exit -1 :timeout? true :okay? false}))

  ([args]
   (let [p  (promise)
         rt (Runtime/getRuntime)
         _
         (when *verbose-logging?*
           (log "[shell] Command: " (str/join " " args)))

         [proc proc-ex]
         (try
           [(.exec rt ^"[Ljava.lang.String;" (into-array (mapv str args))) nil]
           (catch Throwable t [nil t]))

         in->str
         (fn [in]
           (with-open [sw (java.io.StringWriter.)]
             (jio/copy in sw :encoding "UTF-8")
             (str/trim (.toString sw))))]

     (if-let [^Process proc proc]
       (do
         (swap! running-shell-procs_ conj proc)
         (future
           (try
             (with-open [stdout (.getInputStream proc)
                         stderr (.getErrorStream proc)]
               (let [exit-code (.waitFor proc)
                     out (in->str stdout)
                     err (in->str stderr)]
                 (deliver p {:exit exit-code :out out :err err :okay? (zero? exit-code)})))

             (catch Throwable t
               (log "[shell] Exception: " t)
               (deliver p {:exit -1 :ex t :okay? false})
               (.destroy proc))

             (finally (swap! running-shell-procs_ disj proc)))))

       (deliver p {:exit -1 :ex proc-ex :okay? false}))

     (reify
       clojure.lang.IFn    (invoke [_] proc)
       clojure.lang.IDeref (deref  [_] (deref p))
       clojure.lang.IBlockingDeref
       (deref  [_ timeout-msecs timeout-val]
         (deref p timeout-msecs timeout-val))))))

(comment ((shell ["which_INVALID" "ls"])))

(defn rand-free-port
  "Returns a random unbound port in range [49152 65535]."
  []
  (loop [n-attempts 0]
    (if (> n-attempts 3)
      (throw (ex-info "[bench-suite] Gave up trying to find a random free port" {}))
      (let [p (+ 49152 (inc (rand-int 16383)))]
        (if (:okay? (shell 4000 ["nc" "-zv" "-w1" "localhost" (str p)]))
          (recur (inc n-attempts))
          p)))))

(comment (rand-free-port))

(defn with-os-tuning
  "Runs (f) with OS tuning enabled.
  Mostly a proof-of-concept, not significantly used right now."
  [f]
  (let [os
        (when-let [uname (:out (shell 2500 ["uname" "-s"]))]
          (case uname
            "Darwin" :macOS
            "Linux"  :linux
            nil))

        restore-ulimit
        (let [ulimit-n0 (when-let [s (:out (shell 2500 ["ulimit" "-n"]))] (parse-long s))
              ulimit-raised?
              (let [ulimit-n1 80000]
                (when (and ulimit-n0 (> ulimit-n1 ulimit-n0))
                  (when (:okay? (shell 2500 ["ulimit" "-n" ulimit-n1]))
                    (log "[bench-suite] Raised ulimit to " ulimit-n1)
                    true)))]
          (fn []
            (when ulimit-raised?
              (when (:okay? (shell 2500 ["ulimit" "-n" ulimit-n0]))
                (log "[bench-suite] Restored ulimit to original value (" ulimit-n0 ")")))))]

    (case os
      (:linux nil) (try (f) (finally (restore-ulimit)))
      :macOS
      (let [;; Try keep system from sleeping during long benchmarks
            job (shell ["caffeinate -i"])]
        (try
          (f)
          (finally
            (when-let [^Process p (job)] (.destroy p))
            (restore-ulimit)))))))

(comment (with-os-tuning (fn [] (Thread/sleep 2000) :result)))

;;;; Abort util
;; Handy at the REPL to kill a misbehaving bench run

(def ^:dynamic ^:private *aborted?_* nil)
(deftype Abortable [p aborted?_]
  clojure.lang.IDeref         (deref [_                          ] (deref     p))
  clojure.lang.IBlockingDeref (deref [_ timeout-msecs timeout-val] (deref     p timeout-msecs timeout-val))
  clojure.lang.IPending       (isRealized [_]                      (realized? p))
  clojure.lang.IFn
  (invoke [_] ; abort
    (when (compare-and-set! aborted?_ false true)
      (when (deliver p nil)
        (log "[bench-suite] Aborting")
        true))))

(defn abortable [f]
  (let [p         (promise)
        aborted?_ (atom false)]
    (future (binding [*aborted?_* aborted?_] (deliver p (f))))
    (Abortable. p aborted?_)))

(comment
  (def ab1 (abortable (fn [] (Thread/sleep 5000) "done")))
  (deref ab1 1000 :timeout)
  (realized? ab1)
  (ab1))

(defn          aborted? [] (when-let [aborted?_ *aborted?_*] @aborted?_))
(defn throw-if-aborted  []
  (when (aborted?)
    (throw (ex-info "[bench-suite] Aborted!" {:fatal? true}))))

;;;; wrk

(defn run-wrk
  "Runs `wrk` with given options and throws, or returns {:keys [opts measurements}."
  [{:as wrk-opts :keys [port warm-up duration timeout n-threads n-conns keep-alive? warm-up?]}]

  (throw-if-aborted)
  (when-let [warm-up-duration warm-up]
    ;; Run warm-up, discarding results
    (let [warm-up-opts
          (assoc (dissoc wrk-opts :warm-up)
            :after-warm-up warm-up
            :duration      warm-up-duration
            :warm-up?      true)]
      (run-wrk warm-up-opts)))

  (let [path
        (let [{:keys [okay? exit out err]} (shell 2500 ["which" "wrk"])]
          (if okay?
            (str/trim-newline out)
            (throw
              (ex-info
                (str "[wrk] `which wrk` failed, is `wrk` installed and accessible?"
                  {:exit exit :out out :err err :fatal? true})))))

        version
        (let [{:keys [exit out err]} (shell 2500 ["wrk" "--version"])]
          (or
            (second (re-find #"wrk\s(.*)\sCopyright" out))
            (throw
              (ex-info "[wrk] `wrk --version` failed"
                {:exit exit :out out :err err :fatal? true}))))

        _ (when *verbose-logging?* (log "[wrk] Version: " version))

        script-filename
        (let [content  (slurp "test/wrk-script.lua")
              lua-file (java.io.File/createTempFile "http-kit-wrk-script-" ".tmp")]
          (spit             lua-file content)
          (.getAbsolutePath lua-file))

        _
        (do
          (assert duration  "Have wrk duration")
          (assert timeout   "Have wrk timeout")
          (assert n-threads "Have wrk n-threads")
          (assert n-conns   "Have wrk n-conns")
          (assert port      "Have wrk port"))

        wrk-args
        ["wrk"
         "--duration"    duration
         "--timeout"     timeout
         "--threads"     n-threads
         "--connections" n-conns
         "--header"      (if keep-alive? "" "Connection: close")
         "--script"      script-filename
         (str "http://localhost:" port)]

        _ (throw-if-aborted)_
        (if warm-up?
          (log "[wrk] Warming up for " duration)
          (log "[wrk] Benching for "   duration))

        {:keys [okay? exit out err]} @(shell wrk-args)]

    (if warm-up?
      (log "[wrk] Warm-up done")
      (log "[wrk] Benching done"))

    (if-not okay?
      (throw
        (ex-info "[wrk] bench call failed"
          {:args wrk-args :exit exit :out out :err err :fatal? false}))

      (if-let [s (second (re-find #"(?m)^Structured results:(.*)$" out))]
        (when-not warm-up?
          (throw-if-aborted)
          (let [;; See `wrk-script.lua` for column info
                [lmean lstdev lmin lp50 lp75 lp80 lp90 lp98 lp99 lp99-9 lp99-99 lp99-999 lmax
                 usecs reqs bytes
                 err-connect err-read err-write err-status err-timeout]
                (str/split s #",")

                pl  parse-long
                pd  parse-double
                pd0 (comp round0 parse-double)]

            {:opts
             {:version     version
              :path        path
              :warm-up     warm-up
              :duration    duration
              :timeout     timeout
              :n-threads   n-threads
              :n-conns     n-conns
              :keep-alive? keep-alive?}

             :measurements
             {:reqs-per-sec  (round0 (* (/ (pd reqs)  (pl usecs)) 1e6))
              :bytes-per-sec (round0 (* (/ (pl bytes) (pl usecs)) 1e6))
              :usecs         (pl usecs)
              :reqs          (pl reqs)
              :bytes         (pl bytes)

              :latency
              {:mean    (pd0 lmean)
               :stdev   (pd0 lstdev)
               :min     (pd0 lmin)
               :p50     (pd0 lp50)
               :p75     (pd0 lp75)
               :p80     (pd0 lp80)
               :p90     (pd0 lp90)
               :p98     (pd0 lp98)
               :p99     (pd0 lp99)
               :p99-9   (pd0 lp99-9)
               :p99-99  (pd0 lp99-99)
               :p99-999 (pd0 lp99-999)
               :max     (pd0 lmax)}

              :errors
              (let [m {:connect (pl err-connect)
                       :read    (pl err-read)
                       :write   (pl err-write)
                       :status  (pl err-status)
                       :timeout (pl err-timeout)}
                    err-total (reduce + (vals m))]

                (assoc m
                  :total err-total
                  :rate
                  (when (pos? (pl reqs))
                    (/ (double err-total) (pd reqs)))))}}))

        (throw
          (ex-info "[wrk] Unexpected bench call output (no structured results?)"
            {:exit exit :out out :err err :fatal? false}))))))

(defn get-wrk-timeplan
  "Returns a ~reasonable wrk benching timeplan based on given total
  desired runtime across given number of runs."
  [{:keys [runtime n-runs]
    :or   {n-runs 1}}]

  (let [total-msecs     (time-str->msecs runtime)
        total-msecs-per (round0 (/ total-msecs (double n-runs)))

        ss       (fn [msecs] (str (msecs->secs msecs) "s"))
        warm-up  (clamp 3000 10000 (* 0.15 total-msecs-per))
        shutdown (clamp 3000 20000 (* 0.20 total-msecs-per))
        sleep
        (if (== n-runs 1)
          0
          (clamp 1000 10000 (* 0.05 total-msecs-per)))

        duration
        (max 3000
          (-
            total-msecs-per
            (time-str->msecs (ss warm-up))
            (time-str->msecs (ss shutdown))
            (time-str->msecs (ss sleep))))

        total (* n-runs (+ warm-up shutdown sleep duration))]

    {:total-msecs    (round0 total)
     :warm-up-msecs  (round0 warm-up)
     :duration-msecs (round0 duration)
     :shutdown-msecs (round0 shutdown)
     :sleep-msecs    (round0 sleep)

     :total-tstr     (str (msecs->mins total) "m")
     :warm-up-tstr   (ss warm-up)
     :duration-tstr  (ss duration)
     :shutdown-tstr  (ss shutdown)
     :sleep-tstr     (ss sleep)}))

(comment (get-wrk-timeplan {:runtime "10h" :n-runs 480}))

;;;;

(defn get-file
  "Returns a time-stamped `java.io.File` in the output dir."
  [instant filename]
  (let [path (str (or (properties "user.dir") ".") "/benchmarks")
        ts   (format-instant instant)
        file (java.io.File. path (str ts "-" filename))]
    (jio/make-parents file)
    (do               file)))

(comment (get-file (java.time.Instant/now) "foo.edn"))

(def ^:private ^:dynamic *appender* nil)

(defn with-appender [instant filename header f]
  (let [file_ (delay (get-file instant filename))
        init_ (delay (spit @file_ (str (force header) newline)))
        new-appender
        (if *dry-run?*
          (constantly nil)
          (fn
            ([   ] @file_)
            ([row]
             @init_ ; Clear file and write header
             (spit @file_ (str row newline) :append true))))]

    (binding [*appender* (or *appender* new-appender)]
      [(*appender*) (f)])))

(defn append! [row] (when-let [appender *appender*] (appender row)))

(comment
  (with-appender (java.time.Instant/now) "test" "my-header"
    (fn []
      (append! "my-row1")
      (append! "my-row2")
      :return-val)))

;;;;

(defn get-profile [profiles profile]
  (cond
    (vector?  profile) (get-in profiles profile) ; e.g. [:single :http-kit]
    (keyword? profile) (get    profiles profile) ; e.g. :full
    (map?     profile)                  profile  ; Inline profile
    :else
    (throw
      (ex-info "[get-profile] Unknown profile"
        {:profile {:value profile :type (type profile)}}))))

(def this-pid_
  (delay
    (try
      (.pid (java.lang.ProcessHandle/current))
      (catch Throwable _ nil))))

(defn log-bench-progress!
  [log-prefix idx_ n-specs spec]
  (let [idx      (swap! idx_ inc)
        progress (perc (dec idx) n-specs)]
    (log)
    (log log-prefix " *** Bench process PID: " (or @this-pid_ "unknown") " ***")
    (log log-prefix " Current progress: "  (perc (dec idx) n-specs) "->" (perc idx n-specs)  "%")
    (log log-prefix " Using spec " idx "/" n-specs " (natural spec " (:nat-idx spec) "):")))
