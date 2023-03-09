(ns org.httpkit.utils)

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
