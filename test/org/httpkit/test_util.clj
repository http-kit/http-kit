(ns org.httpkit.test-util
  (:use clojure.test)
  (:import [java.io File FileOutputStream FileInputStream]))

(defn- string-80k []
  (apply str (map char
                  (take (* 8 1024)                ; 80k
                        (apply concat (repeat (range (int \a) (int \z))))))))
;; [a..z]+
(def const-string                       ; 8M string
  (let [tmp (string-80k)]
    (apply str (repeat 1024 tmp))))

(defn ^File gen-tempfile
  "generate a tempfile, the file will be deleted before jvm shutdown"
  ([size extension]
     (let [tmp (doto
                   (File/createTempFile "tmp_" extension)
                 (.deleteOnExit))]
       (with-open [w (FileOutputStream. tmp)]
         (.write w ^bytes (.getBytes (subs const-string 0 size))))
       tmp)))

(defn to-int [int-str] (Integer/valueOf int-str))

(def channel-closed (atom false))

(defn- sleep [n]
  (let [end (+ (System/currentTimeMillis) n)]
    (loop []
      (let [time (- end (System/currentTimeMillis))]
        (when (> time 0)
          (try (Thread/sleep time)
               (catch Exception e))
          (recur))))))

(defn check-on-close-called []
  (loop [i 0]
    (when (and (not @channel-closed) (< i 5))
      (sleep 10)                        ; wait most 100ms here
      (recur (inc i))))
  (is @channel-closed)
  (reset! channel-closed false))

(defn close-handler [status]
  (reset! channel-closed true))
