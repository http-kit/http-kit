(ns org.httpkit.benchmark.client
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [ring.adapter.jetty :as jetty]
   [clj-http.client    :as clj-http]
   [org.httpkit.server :as hks]
   [org.httpkit.client :as hkc]
   [org.httpkit.benchmark.utils :as u]
   [org.httpkit.benchmark.server :as server])
  (:import [java.util.concurrent.atomic AtomicLong]))

(comment (remove-ns 'org.httpkit.benchmark.client))

;;;; CSV format

(defn- as-csv-row
  "Returns CSV row headers/vals."
  ([] ; Headers
   (u/join->csv
     [(u/standard-csv-rows)
      ["Client.name" "Client.threads" "Client.keep-alive?" "Client.timeout" "Client.SSL?"]
      ["Bench.duration (µsecs)" "Reqs.total" "Reqs.per-sec"
       "Timeouts.total" "Timeouts.rate" "Errors.total" "Errors.rate"]]))

  ([{:as row-data :keys [client-name bench-opts bench-result]}]
   (u/join->csv
     [(u/standard-csv-rows row-data)
      (u/quoted client-name)
      (let [{:keys [n-threads keep-alive? timeout-msecs ssl?]} bench-opts]
        [n-threads keep-alive? timeout-msecs ssl?])
      (let [{:keys [usecs n-reqs reqs-per-sec
                    n-timeouts timeout-rate n-errors error-rate]} bench-result]

        [usecs n-reqs reqs-per-sec
         n-timeouts (u/format-round4 timeout-rate)
         n-errors   (u/format-round4 error-rate)])])))

(comment [(as-csv-row) (as-csv-row {})])

;;;;

(defn- timeout-ex? [t]
  (when (instance? Throwable t)
    (or
      (when-let [msg   (ex-message t)] (.contains ^String msg "timed out"))
      (when-let [cause (ex-cause   t)] (timeout-ex? cause)))))

(comment
  (timeout-ex?                   (Exception. "Connection timed out"))
  (timeout-ex? (Exception. "foo" (Exception. "Connection timed out"))))

(defn- bench-by-spec
  "Benchmarks client against a running server.
  Very basic: doesn't measure latency distribution stats, etc.
  Good enough for our needs."
  [{:as bench-spec :keys [metadata system-info bench-opts]}]
  (let [{:keys [client-id url n-threads n-reqs keep-alive? ssl? timeout-msecs]
         :or
         {client-id     :http-kit
          n-threads     1
          n-reqs        5000
          keep-alive?   true
          timeout-msecs 2500}} bench-opts]

    (u/throw-if-aborted)
    (let [n-reqs            (long n-reqs)
          n-threads         (long n-threads)
          n-reqs-per-thread (long (quot n-reqs n-threads))
          timeout-msecs     (long timeout-msecs)

          n-successful* (AtomicLong. 0)
          n-errors*     (AtomicLong. 0)

          resp-handler
          (fn resp-handler [resp]
            (try
              (let [resp
                    (if (instance? clojure.lang.IDeref resp)
                      (deref resp timeout-msecs {:status -1})
                      (do    resp))]

                (if (= (:status resp) 200)
                  (.incrementAndGet n-successful*)

                  (when-not (timeout-ex? (:error resp))
                    (.incrementAndGet n-errors*)
                    (u/error! [:bench-client-by-spec/handler-unexpected-resp client-id] resp))))

              (catch Throwable t
                (when-not (timeout-ex? t)
                  (.incrementAndGet n-errors*)
                  (u/error! [:bench-client-by-spec/handler-ex client-id] t)))))

          client-name
          (case client-id
            :http-kit u/dep-http-kit
            :clj-http u/dep-clj-http)

          ns0   (System/nanoTime)
          reqs_ (atom nil)

          shutdown-fn
          (try
            (case client-id
              :http-kit
              (let [worker (hkc/new-worker {})
                    opts   {:insecure? ssl? :keepalive (if keep-alive? 60000 -1) :worker-pool (:pool worker)}
                    opts   (if keep-alive? opts (assoc opts :headers {"connection" "close"}))
                    reqs_  (atom nil)]

                ;; Execute reqs
                (if (== n-reqs-per-thread 1)
                  (dotimes [_ n-reqs-per-thread] (u/throw-if-aborted) (resp-handler (hkc/get url opts)))
                  (dotimes [_ n-reqs-per-thread]
                    (u/throw-if-aborted)
                    (let [reqs (for [_ (range n-threads)] (hkc/get url opts resp-handler))]
                      (reset! reqs_ reqs)
                      (doseq [r reqs]
                        (u/throw-if-aborted)
                        (deref r timeout-msecs nil)))))

                (fn shutdown [] (server/shutdown-pool (:pool worker) 25000)))

              :clj-http
              (let [opts {:insecure? ssl? :async? (> n-reqs-per-thread 1)}
                    opts (if keep-alive? opts (assoc opts :headers {"connection" "close"}))]

                ;; Execute reqs
                (if (== n-reqs-per-thread 1)
                  (dotimes [_ n-reqs-per-thread] (u/throw-if-aborted) (resp-handler (clj-http/get url opts)))
                  (clj-http/with-async-connection-pool
                    {:timeout           5
                     :threads           n-threads
                     :default-per-route n-threads
                     :so-keep-alive     keep-alive?
                     :insecure?         ssl?}
                    (dotimes [_ n-reqs-per-thread]
                      (u/throw-if-aborted)
                      (let [reqs
                            (for [_ (range n-threads)]
                              (clj-http/get url opts resp-handler
                                (fn [ex]
                                  (when-not (timeout-ex? ex)
                                    (.incrementAndGet n-errors*)
                                    (u/error! [:bench-client-by-spec/handler-ex client-id] ex)))))]

                        (reset! reqs_ reqs)
                        (doseq [r reqs]
                          (u/throw-if-aborted)
                          (deref r timeout-msecs nil))))))

                (fn shutdown [])))
            (catch Throwable _))

          nsecs-elapsed (- (System/nanoTime) ns0)
          usecs-elapsed (/ nsecs-elapsed 1e3)
          secs-elapsed  (/ nsecs-elapsed 1e9)

          n-successful  (.get n-successful*)
          n-errors      (.get n-errors*)
          n-timeouts    (- n-reqs (+ n-successful n-errors))

          reqs-per-sec  (/ (double n-reqs) (double secs-elapsed))
          error-rate    (/ (double n-errors)   n-reqs)
          timeout-rate  (/ (double n-timeouts) n-reqs)

          result
          (assoc bench-spec
            :client-name   client-name
            :bench-result
            {:usecs        (u/round0 usecs-elapsed)
             :n-reqs       n-reqs
             :reqs-per-sec (u/round0 reqs-per-sec)
             :n-timeouts   n-timeouts
             :timeout-rate (u/round4 timeout-rate)
             :n-errors     n-errors
             :error-rate   (u/round4 error-rate)})

          csv-row (as-csv-row result)]

      (u/append! csv-row)

      (do ; Clean up
        (doseq [r @reqs_] @r) ; Block as long as necessary
        (when-let [f shutdown-fn] (f)))

      (assoc result :csv csv-row))))

(comment
  (server/with-server
    {:server-opts {:server-id :jetty :port [(u/rand-free-port) (u/rand-free-port)]}}
    (fn [s]
      (let [[port-http port-https] (:port @s)
            n-reqs 20000]
        (time
          [(bench-by-spec {:bench-opts {:client-id :clj-http :n-reqs n-reqs :url (str  "http://localhost:" port-http)}})
           (bench-by-spec {:bench-opts {:client-id :clj-http :n-reqs n-reqs :url (str "https://localhost:" port-https) :ssl? true}})])))))

;;;; Profiles

(def profiles
  "Built-in client benching profiles."
  (let [nc u/num-cores]
    {:full
     {:server-opts
      {:resp-len  [128 1280]
       :resp-work [nil]}

      :bench-opts
      {:client-id     [:http-kit :clj-http]
       :ssl?          [false true]
       :n-threads     [1 nc (* nc 2) (* nc 4)]
       :keep-alive?   [true false]
       :timeout-msecs [2500]}}

     :quick
     {:server-opts
      {:resp-len  [128]
       :resp-work [nil]}

      :bench-opts
      {:client-id     [:http-kit :clj-http]
       :ssl?          [false true]
       :n-threads     [1 nc]
       :keep-alive?   [true false]
       :timeout-msecs [2500]}}}))

(defn bench-by-profile
  "Runs >=1 client benchmarks based on given profile and returns [<File> <result>]."
  [{:keys [metadata system-info port-http port-https profile n-reqs dry-run? skip?]
    :or
    {system-info (u/get-system-info)
     port-http   (u/rand-free-port)
     port-https  (u/rand-free-port)
     profile     :quick
     n-reqs      8000}}]

  (if skip?
    [nil nil]
    (let [specs
          (let [{:keys [server-opts bench-opts]} (u/get-profile profiles profile)
                nat-idx_ (atom 0)]

            (distinct
              (for [resp-len      (:resp-len      server-opts)
                    resp-work     (:resp-work     server-opts)
                    client-id     (:client-id     bench-opts)
                    ssl?          (:ssl?          bench-opts)
                    n-threads     (:n-threads     bench-opts)
                    keep-alive?   (:keep-alive?   bench-opts)
                    timeout-msecs (:timeout-msecs bench-opts)]

                {:nat-idx      (swap! nat-idx_ inc)
                 :metadata     metadata
                 :system-info  system-info
                 ;;
                 :server-opts  {:resp-len resp-len :resp-work resp-work}
                 :bench-opts
                 {:client-id     client-id
                  :n-threads     n-threads
                  :n-reqs        n-reqs
                  :keep-alive?   keep-alive?
                  :timeout-msecs timeout-msecs
                  :ssl?          ssl?
                  :url
                  (if ssl?
                    (str "https://localhost:" port-https)
                    (str  "http://localhost:" port-http))}})))

          n-specs (count specs)]

      (u/throw-if-aborted)
      (u/with-appender (:instant system-info) "client.csv" (delay (as-csv-row))
        (fn []
          (if dry-run?
            {:dry-run? true  :n-specs n-specs :specs (vec specs)}
            {:dry-run? false :n-specs n-specs
             :rows
             (let [shuffled-specs (shuffle specs)
                   handler-fn_    (atom nil)
                   handler-fn     (fn [request] (@handler-fn_ request))
                   idx_           (atom 0)
                   n-total-reqs   (* n-specs (long n-reqs))]

               (u/log "[bench-client-by-profile] *** Starting (" n-specs " specs ~ " n-total-reqs " total reqs) ***")

               (server/with-server
                 {:handler-fn handler-fn
                  :server-opts
                  {:server-id :jetty
                   :port      [port-http port-https]}}

                 (fn [_server]
                   (reduce
                     (fn [acc spec]
                       (let [{:keys [server-opts bench-opts]} spec]
                         (u/log-bench-progress! "[bench-client-by-profile]" idx_ n-specs spec)
                         (u/log "  " (select-keys spec [:server-opts :bench-opts]) u/newline)

                         (reset! handler-fn_ (server/new-handler server-opts))
                         (conj acc (bench-by-spec spec))))
                     [] shuffled-specs))))}))))))

(comment
  (def ab1
    (u/abortable
      (fn []
        (bench-by-profile
          {:profile  :quick
           :dry-run? false #_true
           :n-reqs   2500}))))

  (ab1) ; Abort
  (deref ab1 20000 nil))
