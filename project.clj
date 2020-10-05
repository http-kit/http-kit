(defproject http-kit "2.5.0"
  :author "Feng Shen (@shenfeng)"
  :description "High-performance event-driven HTTP client/server for Clojure"
  :url "http://http-kit.org/"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  []

  :plugins
  [[lein-swank   "1.4.5"]
   [lein-pprint  "1.3.2"]
   [lein-ancient "0.6.15"]
   [lein-codox   "0.10.7"]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms1g" "-Xmx1g"] ; Testing https require more memory

  ;; Oldest version JVM to support:
  :javac-options ["-source" "1.7" "-target" "1.7" "-g"] ; Temp for compiling with older JDK, Ref. #437
  ;;:javac-options ["--release" "7" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :test-selectors
  {:default (complement :benchmark)
   :travis  (complement #(or (:benchmark %) (:skip-travis %)))
   :benchmark :benchmark
   :all (fn [_] true)}

  :profiles
  {:provided {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :test
   {:java-source-paths ["test/java" "src/java"]
    :dependencies
    [[ring/ring-defaults        "0.2.3"] ; TODO Update (causes tests to hang)
     [ring-request-proxy       "0.1.11"]
     [ring-basic-authentication "1.0.5"]
     [org.clojure/data.codec    "0.1.1"]]}

   :dev
   {:resource-paths ["test/resources"]
    :dependencies
    [[org.clojure/clojure            "1.8.0"] ; TODO Update (breaks clj-http)
     [junit/junit                     "4.13"]
     [org.clojure/tools.logging      "1.1.0"]
     [ch.qos.logback/logback-classic "1.2.3"]
     [clj-http                      "3.10.1"]
     [io.netty/netty-all      "4.1.52.Final"]
     [org.clojure/data.json          "1.0.0"]
     [http.async.client              "0.5.2"] ; TODO Update (breaking)
     [compojure                      "1.5.2"] ; TODO Update (breaking)
     [org.clojure/tools.cli          "0.3.3"] ; TODO Update (breaking)
     [ring/ring-jetty-adapter        "1.5.1"] ; TODO Update (breaking)
     [ring/ring-core                 "1.5.1"] ; TODO Update (breaking)
     ]}})
