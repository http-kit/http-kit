(defproject http-kit "2.1.14"
  :description "High-performance event-driven HTTP client/server for Clojure"
  :dependencies [[org.clojure/clojure "1.4.0"]]
  :warn-on-reflection true
  :min-lein-version "2.0.0"
  :url "http://http-kit.org/"
  :javac-options ["-source" "1.6" "-target" "1.6" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#".*java$"]
  :plugins [[lein-swank "1.4.4"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (fn [_] true)}
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :jvm-opts ["-Dclojure.compiler.disable-locals-clearing=true"
             "-Xms1g" "-Xmx1g"]         ; test https require more memory
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :test {:java-source-paths ["test/java" "src/java"]}
             :dev {:dependencies [[junit/junit "4.8.2"]
                                  [org.clojure/tools.logging "0.2.6"]
                                  [ch.qos.logback/logback-classic "1.0.9"]
                                  [clj-http "0.7.2"]
                                  [io.netty/netty "3.6.5.Final"]
                                  [org.clojure/data.json "0.2.1"]
                                  [http.async.client "0.5.2"]
                                  [compojure "1.1.5"]
                                  [org.clojure/tools.cli "0.2.2"]
                                  [ring/ring-jetty-adapter "1.1.8"]
                                  [ring/ring-core "1.1.8"]]}})
