(ns org.httpkit.utils
  (:import
   [java.util.concurrent ThreadPoolExecutor TimeUnit
    BlockingQueue ArrayBlockingQueue LinkedBlockingQueue]))

(defn- java-version
  "Returns Java's major version integer (8, 17, etc.)."
  ;; Ref. `taoensso.encore/java-version`
  (^long [              ] (java-version (System/getProperty "java.version")))
  (^long [version-string]
   (or
     (when-let [^String s version-string]
       (try
         (Integer/parseInt
           (or ; Ref. <https://stackoverflow.com/a/2591122>
             (when     (.startsWith s "1.")                  (.substring s 2 3))    ; "1.6.0_23", etc.
             (let [idx (.indexOf    s ".")] (when (pos? idx) (.substring s 0 idx))) ; "9.0.1",    etc.
             (let [idx (.indexOf    s "-")] (when (pos? idx) (.substring s 0 idx))) ; "16-ea",    etc.
             (do                                                         s)))
         (catch Exception _ nil)))

     (throw
       (ex-info "Failed to parse Java version string (unexpected form)"
         {:version-string version-string})))))

(comment (mapv java-version ["1.6.0_23" "1.8.0_302" "9.0.1" "11.0.12" "16-ea" "17"]))

(let [version_ (delay (java-version))]
  (defn java-version>=
    "Returns true iff Java's major version integer is >= given integer."
    [n] (>= ^long @version_ (long n))))

(defmacro compile-if
  "Evaluates `test`. If it returns logical true (and doesn't throw), expands
  to `then`, otherwise expands to `else`."
  {:style/indent 1}
  [test then else]
  (if (try (eval test) (catch Throwable _ false))
    `(do ~then)
    `(do ~else)))

(defn have-virtual-threads?
  "Returns true iff the current JVM supports virtual threads."
  [] (compile-if (Thread/ofVirtual) true false))

(defn new-worker
  "Returns {:keys [n-cores type pool ...]} where `:pool` is a
  `java.util.concurrent.ExecutorService`."

  [{:as   _internal-opts
    :keys [default-queue-type default-queue-size default-prefix
           n-min-threads-factor n-max-threads-factor
           keep-alive-msecs]
    :or
    {default-queue-type :array
     default-prefix "http-kit-worker-"
     n-min-threads-factor 1
     n-max-threads-factor 1}}

   {:as   _user-opts
    :keys [n-min-threads n-max-threads n-threads
           queue-type queue-size prefix allow-virtual?]
    :or   {allow-virtual? true}}]

  (let [;; Calculate at runtime to prevent Graal issues
        n-cores (.availableProcessors (Runtime/getRuntime))
        new-virtual-pool
        (compile-if (Thread/ofVirtual)
          (fn [] (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))
          nil)]

    (if (and allow-virtual? new-virtual-pool)

      ;; Use JVM 21+ virtual threads
      {:type    :virtual
       :n-cores n-cores
       :pool    (new-virtual-pool)}

      ;; Use fixed thread pool
      (let [factory          (org.httpkit.PrefixThreadFactory. (or prefix default-prefix))
            n-min-threads    (long (or n-min-threads n-threads (max 2 (Math/round (* n-cores (double n-min-threads-factor))))))
            n-max-threads    (long (or n-max-threads n-threads (max 2 (Math/round (* n-cores (double n-max-threads-factor))))))
            keep-alive-msecs (long (or keep-alive-msecs 0))

            queue-size (or queue-size default-queue-size)
            queue
            (case (or queue-type default-queue-type)
              :array (ArrayBlockingQueue. (int queue-size))
              :linked
              (if queue-size
                (LinkedBlockingQueue. (int queue-size))
                (LinkedBlockingQueue.)))]

        {:type          :fixed
         :n-cores       n-cores
         :n-min-threads n-min-threads
         :n-max-threads n-max-threads
         :queue-type    queue-type
         :queue-size    queue-size
         :queue         queue
         :pool
         (ThreadPoolExecutor.
           (int n-min-threads)
           (int n-max-threads)
           (int keep-alive-msecs) TimeUnit/MILLISECONDS
           ^BlockingQueue queue factory)}))))

(comment (new-worker {} {}))
