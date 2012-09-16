(defproject me.shenfeng/http-kit "1.1.1"
  :description "Event driven HTTP server and HTTP client in java and clojure, only depend on JDK."
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.slf4j/slf4j-api "1.6.4"]]
  :warn-on-reflection true
  :javac-options {:debug "true" :fork "true" :source "1.6" :target "1.6"}
  :java-source-path "src/java"
  :test-paths ["test" "example"]
  :dev-dependencies [[swank-clojure "1.4.0"]
                     [junit/junit "4.8.2"]
                     [ch.qos.logback/logback-classic "1.0.1"]
                     [clj-http "0.1.3"]
                     [org.clojure/data.json "0.1.2"]
                     [org.jsoup/jsoup "1.6.4"]
                     [org.clojure/tools.logging "0.2.3"]
                     [compojure "1.0.2"]
                     [me.shenfeng/async-ring-adapter "1.0.1"]
                     [org.clojure/tools.cli "0.2.1"]
                     [ring/ring-jetty-adapter "0.3.11"]
                     [ring/ring-core "1.1.0-RC1"]])
