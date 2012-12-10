(defproject me.shenfeng/http-kit "1.2"
  :description "Event driven HTTP server and HTTP client in java and clojure"
  :dependencies [[org.clojure/clojure "1.4.0"]]
  :warn-on-reflection true
  :url "https://github.com/shenfeng/http-kit"
  :javac-options ["-source" "1.6" "-target" "1.6"]
  :java-source-paths ["src/java"]
  :test-paths ["test" "examples"]
  :jar-exclusions [#".*java$"]
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]}
             :dev {:dependencies [[swank-clojure "1.4.0"]
                                  [junit/junit "4.8.2"]
                                  [org.slf4j/slf4j-api "1.6.4"]
                                  [ch.qos.logback/logback-classic "1.0.1"]
                                  [clj-http "0.1.3"]
                                  [org.clojure/data.json "0.1.2"]
                                  [org.jsoup/jsoup "1.7.1"]
                                  [org.clojure/tools.logging "0.2.3"]
                                  [compojure "1.0.2"]
                                  [me.shenfeng/async-ring-adapter "1.0.1"]
                                  [org.clojure/tools.cli "0.2.1"]
                                  [ring/ring-jetty-adapter "0.3.11"]
                                  [ring/ring-core "1.1.0-RC1"]]}})
