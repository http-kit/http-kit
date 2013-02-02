(ns org.httpkit.test-util
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
