(defproject http-kit "2.8.0-beta1"
  :author "Feng Shen (@shenfeng) and contributors"
  :description "Simple, high-performance event-driven HTTP client+server for Clojure"
  :url "https://github.com/http-kit/http-kit"

  :license
  {:name "Apache License, Version 2.0"
   :url  "https://www.apache.org/licenses/LICENSE-2.0.html"}

  :global-vars {*warn-on-reflection* true}

  :dependencies []

  :javac-options     ["--release" "8" "-g"] ; Oldest version JVM to support
  :java-source-paths ["src/java"]
  :jar-exclusions    [#"^java.*"] ; exclude the java directory in source path

  :test-paths ["test"]
  :test-selectors
  {:all     (constantly true)
   :default (complement :benchmark)
   :gha     (complement #(or (:benchmark %) (:skip-gha %)))
   :benchmark :benchmark}

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :provided {:dependencies [[org.clojure/clojure "1.11.1"]]}
   :c1.11    {:dependencies [[org.clojure/clojure "1.11.1"]]}
   :c1.10    {:dependencies [[org.clojure/clojure "1.10.3"]]}
   :c1.9     {:dependencies [[org.clojure/clojure "1.9.0"]]}

   :test
   {:java-source-paths ["test/java" "src/java"]
    :jvm-opts ["-server" "-Xms1024m" "-Xmx2048m"]
    :dependencies
    [[ring/ring-defaults        "0.4.0"]
     [ring-request-proxy        "0.1.11"]
     [ring-basic-authentication "1.2.0"]
     [org.clojure/data.codec    "0.1.1"]]}

   :dev
   [:c1.11 :test
    {:resource-paths ["test/resources"]
     :dependencies
     [[org.clojure/clojure            "1.11.1"]
      [junit/junit                    "4.13.2"]
      [org.clojure/tools.logging       "1.2.4"]
      [ch.qos.logback/logback-classic "1.4.11"]
      [clj-http                       "3.12.3"]
      [io.netty/netty-all       "4.1.98.Final"]
      [org.clojure/data.json           "2.4.0"]
      [http.async.client               "1.3.0"] ; Newer versions fail
      [compojure                       "1.7.0"]
      [org.clojure/tools.cli         "1.0.219"]
      [ring/ring-jetty-adapter         "1.5.1"] ; Newer versions fail
      [ring/ring-core                 "1.10.0"]]

     :plugins
     [[lein-pprint  "1.3.2"]
      [lein-ancient "0.7.0"]
      [lein-codox   "0.10.8"]]}]

   :nrepl
   {:dependencies [[nrepl "1.0.0"]]
    :plugins
    [[cider/cider-nrepl         "0.38.1"]
     [mx.cider/enrich-classpath "1.18.0"]]}})
