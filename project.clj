(defproject me.shenfeng/http-kit "1.3-SNAPSHOT"
  :description "High-performance event-driven Clojure HTTP client/server"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.macro "0.1.1"]]
  :warn-on-reflection true
  :min-lein-version "2.0.0"
  :url "https://github.com/shenfeng/http-kit"
  :javac-options ["-source" "1.6" "-target" "1.6" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#".*java$"]
  :plugins [[lein-swank "1.4.4"]]
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]}
             :dev {:dependencies [[junit/junit "4.8.2"]
                                  [org.slf4j/slf4j-api "1.6.4"]
                                  [ch.qos.logback/logback-classic "1.0.1"]
                                  [clj-http "0.6.0"]
                                  [org.clojure/data.json "0.1.2"]
                                  [org.jsoup/jsoup "1.7.1"]
                                  [org.clojure/tools.logging "0.2.3"]
                                  [compojure "1.0.2"]
                                  [me.shenfeng/async-ring-adapter "1.0.1"]
                                  [org.clojure/tools.cli "0.2.1"]
                                  [ring/ring-jetty-adapter "1.2.0-SNAPSHOT"]
                                  [ring/ring-core "1.1.6"]]}})
