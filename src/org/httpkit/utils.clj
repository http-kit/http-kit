(ns org.httpkit.utils
  (:import
   [java.util.concurrent ThreadPoolExecutor TimeUnit
    BlockingQueue ArrayBlockingQueue LinkedBlockingQueue]))

(defn- parse-java-version
  "Ref. https://stackoverflow.com/a/2591122"
  [^String s]
  (let [dot-idx  (.indexOf s ".")  ; e.g. "1.6.0_23"
        dash-idx (.indexOf s "-")] ; e.g. "16-ea"
    (cond
      ;; e.g. "1.6.0_23"
      (.startsWith s "1.") (Integer/parseInt (.substring s 2 3))
      (pos? dot-idx)       (Integer/parseInt (.substring s 0 dot-idx))
      (pos? dash-idx)      (Integer/parseInt (.substring s 0 dash-idx))
      :else                (Integer/parseInt             s))))

(comment ; [6 8 9 11 16 17]
  [(parse-java-version "1.6.0_23")
   (parse-java-version "1.8.0_302")
   (parse-java-version "9.0.1")
   (parse-java-version "11.0.12")
   (parse-java-version "16-ea")
   (parse-java-version "17")])

(def ^:private java-version_
  (delay (parse-java-version (str (System/getProperty "java.version")))))

(defn java-version>= [n] (>= ^long @java-version_ (long n)))

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
