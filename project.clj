(defproject http-kit "2.3.0-RC1"
  :author "Feng Shen (@shenfeng)"
  :description "High-performance event-driven HTTP client/server for Clojure"
  :url "http://http-kit.org/"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  [[org.clojure/clojure "1.5.1"]]

  :plugins
  [[lein-swank   "1.4.5"]
   [lein-pprint  "1.1.2"]
   [lein-ancient "0.6.10"]
   [lein-codox   "0.10.3"]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms1g" "-Xmx1g"] ; Testing https require more memory

  :javac-options ["-source" "1.6" "-target" "1.6" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :test-selectors
  {:default (complement :benchmark)
   :travis  (complement #(or (:benchmark %) (:skip-travis %)))
   :benchmark :benchmark
   :all (fn [_] true)}

  :profiles
  {:test {:java-source-paths ["test/java" "src/java"]
          :dependencies [[ring/ring-defaults "0.2.3"]
                         [ring-request-proxy "0.1.4"]
                         [ring-basic-authentication "1.0.5"]
                         [org.clojure/data.codec "0.1.0"]]}
   :dev  {:dependencies
          [[org.clojure/clojure "1.8.0"]
           [junit/junit "4.12"]
           [org.clojure/tools.logging "0.3.1"]
           [ch.qos.logback/logback-classic "1.2.3"]
           [clj-http "2.1.0"] ; TODO Update (breaking?)
           [io.netty/netty "3.6.5.Final"] ; TODO Update (breaking)
           [org.clojure/data.json "0.2.6"]
           [http.async.client "0.5.2"] ; TODO Update (breaking)
           [compojure "1.5.2"]
           [org.clojure/tools.cli "0.3.3"] ; TODO Update (breaking)
           [ring/ring-jetty-adapter "1.5.1"]
           [ring/ring-core "1.5.1"]]}})
