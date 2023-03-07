(defproject http-kit "2.6.0"
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
  [[lein-swank                "1.4.5"]
   [lein-pprint               "1.3.2"]
   [lein-ancient              "0.7.0"]
   [lein-codox                "0.10.8"]
   [cider/cider-nrepl         "0.30.0"]
   [mx.cider/enrich-classpath "1.9.0"]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms1g" "-Xmx1g"] ; Testing https require more memory

  ;; Oldest version JVM to support:
  :javac-options ["--release" "7" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :test-selectors
  {:default (complement :benchmark)
   :gha  (complement #(or (:benchmark %) (:skip-gha %)))
   :benchmark :benchmark
   :all (fn [_] true)}

  :profiles
  {:provided {:dependencies [[org.clojure/clojure "1.5.1"]]}
   :test
   {:java-source-paths ["test/java" "src/java"]
    :dependencies
    [[ring/ring-defaults        "0.3.3"]
     [ring-request-proxy       "0.1.11"]
     [ring-basic-authentication "1.1.1"]
     [org.clojure/data.codec    "0.1.1"]]}

   :dev
   {:resource-paths ["test/resources"]
    :dependencies
    [[org.clojure/clojure             "1.8.0"] ; TODO Update (blocked on `http.async.client` update`)
     [nrepl                           "1.0.0"]
     [junit/junit                    "4.13.2"]
     [org.clojure/tools.logging       "1.2.4"]
     [ch.qos.logback/logback-classic "1.2.11"]
     [clj-http                       "3.12.3"]
     [io.netty/netty-all       "4.1.52.Final"]
     [org.clojure/data.json           "2.4.0"]
     [http.async.client               "0.5.2"] ; TODO Update (breaking)
     [compojure                       "1.7.0"]
     [org.clojure/tools.cli         "1.0.206"]
     [ring/ring-jetty-adapter         "1.5.1"] ; TODO Update (breaking)
     [ring/ring-core                  "1.9.5"]
     ]}})
