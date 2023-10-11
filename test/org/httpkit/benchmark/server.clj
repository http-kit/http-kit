(ns org.httpkit.benchmark.server
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [ring.adapter.jetty :as jetty]
   [org.httpkit.server :as hks]
   [org.httpkit.client :as hkc]
   [org.httpkit.benchmark.utils :as u]))

(comment (remove-ns 'org.httpkit.benchmark.server))

;;;; CSV format

(defn- as-csv-row
  "Returns CSV row headers/vals."
  ([] ; Headers
   (u/join->csv
     [(u/standard-csv-rows)
      ["Server.name" "Server.pool-type" "Server.min-threads" "Server.max-threads" "Server.queue-size"]
      ["wrk.version" "wrk.warm-up" "wrk.duration" "wrk.timeout" "wrk.threads" "wrk.conns" "wrk.keep-alive?"]
      ["wrk.error" "wrk.duration (µsecs)" "Reqs.total" "Reqs.per-sec" "Bytes.total" "Bytes.per-sec"]
      ["Latency.mean (µsecs)" "Latency.stdev (µsecs)" "Latency.min (µsecs)"
       "Latency.p50 (µsecs)"  "Latency.p75 (µsecs)"   "Latency.p80 (µsecs)"
       "Latency.p90 (µsecs)"  "Latency.p98 (µsecs)"   "Latency.p99 (µsecs)"
       "Latency.p99-9 (µsecs)" "Latency.p99-99 (µsecs)" "Latency.p99-999 (µsecs)"
       "Latency.p100 (µsecs)"]
      ["Errors.rate" "Errors.total" "Errors.connect" "Errors.read" "Errors.write" "Errors.status" "Errors.timeout"]]))

  ([{:as row-data :keys [server-name worker wrk-result]}]
   (u/join->csv
     [(u/standard-csv-rows row-data)
      (u/quoted server-name)
      (let [{:keys [type n-min-threads n-max-threads queue-size]} worker]
        [(when type (name type)) n-min-threads n-max-threads queue-size])

      (let [{:keys [version warm-up duration timeout n-threads n-conns keep-alive?]} (:opts wrk-result)]
        [version warm-up duration timeout n-threads n-conns keep-alive?])

      (:error wrk-result)

      (let [{:keys [measurements]}   wrk-result
            {:keys [latency errors]} measurements]

        [(let [{:keys [usecs reqs reqs-per-sec bytes bytes-per-sec]} measurements]
           [usecs reqs reqs-per-sec bytes bytes-per-sec])

         (let [{:keys [mean stdev min p50 p75 p80 p90 p98 p99 p99-9 p99-99 p99-999 max]} latency]
           [mean stdev min p50 p75 p80 p90 p98 p99 p99-9 p99-99 p99-999 max])

         (let [{:keys [rate total connect read write status timeout]} errors]
           [(u/format-round4 rate) total connect read write status timeout])])])))

(comment [(as-csv-row) (as-csv-row {})])

;;;; Server API
;; A consistent interface for all servers

(defn shutdown-pool
  [^java.util.concurrent.ExecutorService pool timeout-msecs]
  (u/log "[server] Shutting down worker pool")
  (.shutdown         pool)
  (.awaitTermination pool (int timeout-msecs) java.util.concurrent.TimeUnit/MILLISECONDS)
  (.shutdownNow      pool)
  (.isTerminated     pool))

(defprotocol IServer
  "Servers must implement this protocol and `IDeref` to
  support benching.

  See current implementors as examples, and
  `org.httpkit.utils/new-worker` for worker details."
  (^:private server-start [_ handler port {:as worker-opts :keys [n-threads n-min-threads n-max-threads queue-size allow-virtual?]}])
  (^:private server-stop  [_ timeout-msecs]))

(deftype ServerHttpKit [state_]
  clojure.lang.IDeref
  (deref [_]
    (let [{:keys [worker server port]} @state_]
      {:server-name u/dep-http-kit
       :running?    (boolean server)
       :worker      worker
       :port        port}))

  IServer
  (server-start [_ handler port worker-opts]
    (when (nil? @state_)
      (let [worker (hks/new-worker worker-opts)
            server (hks/run-server handler
                     {:port port :worker-pool (:pool worker)})]
        (reset! state_ {:worker worker :server server :port port})
        true)))

  (server-stop [_ timeout-msecs]
    (when-let [{:keys [worker server]} @state_]
      (server                       timeout-msecs)
      (shutdown-pool (:pool worker) timeout-msecs)
      (reset! state_ nil)
      true)))

(deftype ServerJetty [state_]
  clojure.lang.IDeref
  (deref [_]
    (let [{:keys [worker server port]} @state_]
      {:server-name u/dep-jetty
       :running?    (boolean server)
       :worker      worker
       :port        port}))

  IServer
  (server-start [_ handler port worker-opts]
    (when (nil? @state_)
      (let [worker (hks/new-worker worker-opts)
            pool
            (let [{:keys [^java.util.concurrent.ExecutorService pool]} worker]
              (if (= (:type worker) :virtual)
                ;; Hack wrapper until  `ring-jetty-adapter` has better native
                ;; support, though this seems to work well enough in the meantime
                (reify
                  org.eclipse.jetty.util.thread.ThreadPool
                  (execute        [_ job] (.submit pool job))
                  (getThreads     [_] 1)
                  (getIdleThreads [_] 128)
                  (isLowOnThreads [_] false))

                ;; Alternative hack, worse since involves unnecessary pooling
                ;; (org.eclipse.jetty.util.thread.QueuedThreadPool. 1024 0 60000 0 nil nil
                ;;   (.factory (.name (Thread/ofVirtual) "jetty-worker-" 0)))

                ;; Need to wrap our `ExecutorService`
                (org.eclipse.jetty.util.thread.ExecutorThreadPool.
                  ^java.util.concurrent.ThreadPoolExecutor pool)))

            server
            (let [[port-http port-https] (if (vector? port) port [port nil])
                  base-opts {:port port-http :thread-pool pool :join? false}
                  ssl-opts
                  (when port-https
                    {:ssl?         true
                     :ssl-port     port-https
                     :key-password "123456"
                     :keystore     "test/ssl_keystore"})]
              (jetty/run-jetty handler (merge base-opts ssl-opts)))]

        (reset! state_ {:worker worker :server server :port port})
        true)))

  (server-stop [_ timeout-msecs]
    (when-let [{:keys [worker server]} @state_]
      (let [^org.eclipse.jetty.server.Server server server]
        (.setStopTimeout server (long timeout-msecs))
        (.stop           server)
        (shutdown-pool (:pool worker) timeout-msecs))
      (reset! state_ nil)
      true)))

(defn- new-server [server-id]
  (case server-id
    :http-kit (ServerHttpKit. (atom nil))
    :jetty    (ServerJetty.   (atom nil))
    (throw
      (ex-info "[new-server] Unexpected server id"
        {:server-id {:value server-id :type (type server-id)}
         :expected  #{:http-kit :jetty}}))))

(comment (new-server :http-kit))
(comment
  ;; Test basic server functionality
  (let [p (rand-free-port)
        s (new-server :jetty)
        _ (server-start s (new-handler {}) p {})
        m @s
        r @(hkc/get (str "http://localhost:" p) {:headers {"connection" "close"}})
        _ (server-stop s 2000)]
    [r m]))

(defn- rand-msecs ^long [^long min-msecs ^long max-msecs]
  (+ min-msecs (long (* (Math/random) (- max-msecs min-msecs)))))

(comment (rand-msecs 10 70))

(defn- hot-work
  "Simulates work for given msecs. Meaningfully different than thread
  sleeping, esp. when using virual threads."
  [^long msecs]
  (let [t0 (System/currentTimeMillis)]
    (loop [n 0.0]
      (when (< (- (System/currentTimeMillis) t0) msecs)
        (u/throw-if-aborted)
        (recur (+ n (Math/random)))))))

(comment (hot-work 2000))

(defn new-handler
  "Returns a new (fn ring-request-handler [ring-request])=>ring-response."
  [{:as server-opts :keys [resp-len resp-work]}]
  (let [;; NB Jetty seems to inexplicably omit the content-length header when
        ;; keep-alive is disabled, causing wrk to report 100% of reads as errors.
        body (if resp-len (reduce str (repeatedly resp-len #(rand-int 10))) "")
        response
        {:status 200
         :body   body
         :headers
         {"content-type"  "text/plain"
          "content-length" (str (count body))}}

        {:keys [sleep hot]} resp-work]

    (fn [request]
      (try
        (when-let [[min max] (not-empty sleep)] (u/throw-if-aborted) (Thread/sleep (int (rand-msecs min max))))
        (when-let [[min max] (not-empty hot)]                        (hot-work          (rand-msecs min max)))
        response
        (catch InterruptedException _ nil)))))

(comment ((new-handler {:resp-len 128}) {}))

(defn with-server
  "Creates a new server with given opts and calls (f server), returning its result.
  Always shuts down `f` when done."
  [{:keys [handler-fn server-opts worker-opts timeplan]} f]
  (let [{:keys [server-id port]
         :or   {server-id :http-kit port (u/rand-free-port)}}
        server-opts

        {:keys [shutdown-msecs sleep-msecs]
         :or   {shutdown-msecs 25000
                sleep-msecs     2000}}
        timeplan

        handler (or handler-fn (new-handler server-opts))
        server  (new-server server-id)]

    (u/log "[with-server] Starting " (name server-id) " on port " port)
    (server-start server handler port worker-opts)
    (try
      (f server)
      (finally
        (let [verbose? u/*verbose-logging?*
              t0 (System/currentTimeMillis)]
          (u/log "[with-server] Shutting down " (name server-id) " on port " port)
          (server-stop server shutdown-msecs)
          (u/log "[with-server] Shutdown finished in " (u/secs-since t0) " seconds")

          (when verbose? (u/log "[with-server] Killing running procs"))
          (u/kill-running-shell-procs!) ; Should be unnecessary, but being safe

          ;; Give system a little time to rest / clean up
          (when (pos? sleep-msecs)
            (u/log "[with-server] Sleeping " (u/msecs->secs sleep-msecs) " seconds")
            (Thread/sleep (int sleep-msecs)))

          (when verbose? (u/log "[with-server] Requesting system GC"))
          (System/gc))))))

(comment
  (with-server {} (fn [_] :foo))
  (with-server {}
    (fn [server]
      @(hkc/get (str "http://localhost:" (:port @server))
         {}))))

;;;;

(defn- bench-by-spec
  "Creates a new server and runs >=1 wrk benchmarks specified by spec."
  [{:as    bench-spec
    :keys [metadata system-info
           server-opts worker-opts wrk-opts timeplan]}]

  (u/throw-if-aborted)
  (let [wrk-opts-vec (if (vector? wrk-opts) wrk-opts [wrk-opts])
        n-total      (count wrk-opts-vec)

        server-opts  (u/or-defaults server-opts {:server-id :http-kit :port (u/rand-free-port)})
        worker-opts  (u/or-defaults worker-opts {:n-threads u/num-cores :queue-size 8192})
        wrk-opts-vec
        (mapv
          #(u/or-defaults %
             (let [half-cores (u/round0 (min 1 (* u/num-cores 0.5)))]
               {:n-conns half-cores :n-threads half-cores :keep-alive? true
                :warm-up "5s" :duration "5s" :timeout "2s"}))
          wrk-opts-vec)]

    (with-server
      (assoc bench-spec
        :server-opts server-opts
        :worker-opts worker-opts)

      (fn [server]
        (vec
          (map-indexed
            (fn [idx wrk-opts]
              (u/throw-if-aborted)
              (u/log "[bench-server-by-spec] Will bench opts " (inc idx) "/" n-total " with wrk-opts:")
              (u/log "  " wrk-opts u/newline)
              (let [{:keys [worker server-name]} @server
                    wrk-result
                    (try
                      (u/run-wrk (assoc wrk-opts :port (:port server-opts)))
                      (catch Throwable t
                        (u/error! [:bench-server-by-spec (:server-id server-opts)] t)
                        (if (:fatal? (ex-data t))
                          (do (u/log "[bench-server-by-spec] Exception (fatal): "     t) (throw t))
                          (do (u/log "[bench-server-by-spec] Exception (non-fatal): " t) {:error (str t)}))))

                    result
                    (assoc bench-spec
                      :worker     (dissoc worker :pool :queue :n-cores)
                      :wrk-result wrk-result)

                    csv-row (as-csv-row result)]

                ;; Print main results as a sanity check while running
                (let [{:keys [error-rate] :as snippet}
                      (let [{m :measurements} wrk-result]
                        {:reqs-per-sec    (get-in m [:reqs-per-sec])
                         :latency-p98 (-> (get-in m [:latency :p98]) (str " µsecs"))
                         :error-rate  (-> (get-in m [:errors  :rate]) u/format-round4)})]

                  (u/log "[bench-server-by-spec] wrk result snippet: " snippet)
                  (when (>= (parse-double error-rate) 0.6)
                    ;; Server is either overwhelmed or otherwise malfunctioning
                    (u/log "[bench-server-by-spec] *** Warning: abnormally high wrk error rate (" error-rate ") ***")))

                (u/append!         csv-row)
                (assoc result :csv csv-row)))
            wrk-opts-vec))))))

(comment
  ;; Basic bench of a single server and set of opts
  (u/with-os-tuning
    (fn []
      (bench-by-spec
        {:server-opts  {:server-id :jetty #_:http-kit :resp-len 128}
         :worker-opts  {}
         :wrk-opts-vec [{:n-threads 2 :n-conns 2 :duration "3s" :timeout "1s" :keep-alive? false}]}))))

;;;; Profiles

(def profiles
  "Built-in server benching profiles."
  (let [nc u/num-cores

        ;;; Defaults chosen based on experiments
        n-wrk-threads (max 1 (u/round0 (* nc 0.333)))
        queue-size 65536 ; ~Small but reasonable
        n-wrk-conns-basic
        (cond
          (>= nc 32) [256]
          (>= nc 16) [128]
          (>= nc  8) [ 64]
          (>= nc  4) [ 32]
          :else      [  8])]

    {:full
     {:comments "Full profile"
      :server-opts
      {:server-id   [:http-kit :jetty]
       :resp-len    [128 1280]
       :resp-work   [nil {:sleep [10 70] :hot [0 20]} {:sleep [10 110] :hot [0 40]}]}

      :worker-opts
      {:queue-size  [queue-size]
       :n-threads   [(* nc 2) (* nc 8) (* nc 16) nil]}

      :wrk-opts
      {:timeout     ["2s"]
       :n-threads   [n-wrk-threads]
       :keep-alive? [true false]
       :n-conns
       (cond
         (>= nc 64) [64 1024 4096 32768 65536]
         (>= nc 32) [64  512 2048 16384 32768]
         (>= nc 16) [64  256 1024  2048  4096]
         (>= nc  8) [32   48   64    96   128]
         (>= nc  4) [4     8   16    24    32]
         :else      [1     2    4     8    16])}}

     :quick
     {:comments "Quick profile (default)"
      :server-opts
      {:server-id   [:http-kit :jetty]
       :resp-len    [128]
       :resp-work   [{:sleep [10 70] :hot [0 20]}]}

      :worker-opts
      {:queue-size  [queue-size]
       :n-threads   [(* nc 2) (* nc 16) nil]}

      :wrk-opts
      {:timeout     ["2s"]
       :n-threads   [n-wrk-threads]
       :keep-alive? [true false]
       :n-conns     n-wrk-conns-basic}}

     :single
     (let [base
           {:comments "Single-run profile"
            :server-opts
            {:server-id   [:REPLACE-ME]
             :resp-len    [128]
             :resp-work   [{:sleep [10 70] :hot [0 20]}]}

            :worker-opts
            {:queue-size  [1024]
             :n-threads   [(* nc 4)]}

            :wrk-opts
            {:timeout     ["1s"]
             :n-threads   [n-wrk-threads]
             :keep-alive? [true]
             :n-conns     n-wrk-conns-basic}}]

       {:jetty    (assoc-in base [:server-opts :server-id] [:jetty])
        :http-kit (assoc-in base [:server-opts :server-id] [:http-kit])})}))

(defn bench-by-profile
  "Runs >=1 server benchmarks based on given profile and returns [<File> <result>]."
  [{:keys [metadata system-info port profile runtime dry-run? skip?]
    :or
    {system-info (u/get-system-info)
     port        (u/rand-free-port)
     profile     :quick}}]

  (if skip?
    [nil nil]
    (let [specs
          (let [have-vts? (u/have-virtual-threads?)
                nat-idx_  (atom 0)

                {:keys [server-opts worker-opts wrk-opts]}
                (u/get-profile profiles profile)]

            (distinct
              (for [server-id        (:server-id   server-opts)
                    resp-len         (:resp-len    server-opts)
                    resp-work        (:resp-work   server-opts)
                    n-worker-threads (:n-threads   worker-opts)
                    queue-size       (:queue-size  worker-opts)
                    timeout          (:timeout     wrk-opts)
                    n-wrk-threads    (:n-threads   wrk-opts)
                    n-conns          (:n-conns     wrk-opts)
                    keep-alive?      (:keep-alive? wrk-opts)]

                (let [allow-virtual? (and have-vts? (nil? n-worker-threads))]

                  {:nat-idx      (swap! nat-idx_ inc)
                   :metadata     metadata
                   :system-info  system-info
                   ;;
                   :server-opts  {:server-id server-id :port port :resp-len resp-len :resp-work resp-work}
                   :worker-opts  {:n-threads n-worker-threads :allow-virtual? allow-virtual? :queue-size queue-size}
                   :wrk-opts     {:timeout timeout :n-threads n-wrk-threads :n-conns n-conns :keep-alive? keep-alive?}}))))

          n-specs (count specs)
          timeplan
          (let [runtime (or runtime (str (* n-specs 20) "s"))]
            (u/get-wrk-timeplan {:n-runs n-specs :runtime runtime}))

          specs
          (let [{:keys [warm-up-tstr duration-tstr]} timeplan]
            (mapv
              (fn [spec]
                (-> spec
                  (assoc  :timeplan timeplan)
                  (update :wrk-opts #(merge % {:warm-up warm-up-tstr :duration duration-tstr}))))
              specs))]

      (u/throw-if-aborted)
      (u/with-appender (:instant system-info) "server.csv" (delay (as-csv-row))
        (fn []
          (if dry-run?
            {:dry-run? true :n-specs n-specs :specs (vec specs)}
            (if (> n-specs 1024)
              (throw
                (ex-info "[bench-server-by-profile] Too many specs to bench (> 1024), try reducing variations in profile"
                  {:n-specs n-specs :profile profile}))
              (do
                (u/log "[bench-server-by-profile] *** Starting (" n-specs " specs ~ " (:total-tstr timeplan) " mins) ***")
                {:dry-run? false :n-specs n-specs
                 :rows
                 (let [shuffled-specs (shuffle specs)
                       idx_           (atom 0)]
                   (u/with-os-tuning
                     (fn []
                       (reduce
                         (fn [acc spec]
                           (u/log-bench-progress! "[bench-server-by-profile]" idx_ n-specs spec)
                           (u/log "  " (select-keys spec [:server-opts :worker-opts]) u/newline)
                           (into acc (bench-by-spec spec)))
                         [] shuffled-specs))))}))))))))

(comment
  (def ab1
    (u/abortable
      (fn []
        (bench-by-profile
          {:profile  [:single :http-kit]
           :dry-run? false #_true
           :runtime  "10s"}))))

  (ab1) ; Abort
  (deref ab1 20000 nil))
