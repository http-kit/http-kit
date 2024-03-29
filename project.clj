(defproject http-kit "2.8.0-RC1"
  :author "Feng Shen (@shenfeng) and contributors"
  :description "Simple, high-performance event-driven HTTP client+server for Clojure"
  :url "https://github.com/http-kit/http-kit"

  :license
  {:name "Apache License, Version 2.0"
   :url  "https://www.apache.org/licenses/LICENSE-2.0.html"}

  :global-vars {*warn-on-reflection* true}

  :javac-options     ["--release" "8" "-g"] ; Oldest version JVM to support
  :java-source-paths ["src/java"]
  :jar-exclusions    [#"^java.*"] ; Exclude Java dir from source path

  :test-paths ["test"]
  :test-selectors
  {:all (constantly true)
   :ci  (complement :skip-ci)}

  :dependencies []

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :provided {:dependencies [[org.clojure/clojure "1.11.1"]]}
   :c1.11    {:dependencies [[org.clojure/clojure "1.11.1"]]}
   :c1.10    {:dependencies [[org.clojure/clojure "1.10.3"]]}
   :c1.9     {:dependencies [[org.clojure/clojure "1.9.0"]]}

   :no-ring-websockets
   {:jvm-opts ["-Dhttp-kit.no-ring-websockets=true"]}

   :dev
   {:jvm-opts ["-server" "-Xms1024m" "-Xmx2048m"]
    :java-source-paths ["test/java" "src/java"]
    :resource-paths    ["test/resources"]
    :dependencies
    [[org.clojure/clojure            "1.11.1"]
     [ring/ring-core                 "1.11.0"]
     [ring/ring-jetty-adapter        "1.11.0"]
     [ring/ring-defaults              "0.4.0"]
     [ring-request-proxy             "0.1.11"]
     [ring-basic-authentication       "1.2.0"]
     [org.clojure/data.codec          "0.2.0"]
     [junit/junit                    "4.13.2"]
     [org.clojure/tools.logging       "1.3.0"]
     [ch.qos.logback/logback-classic  "1.5.0"]
     [clj-http                       "3.12.3"]
     [io.netty/netty-all       "4.1.98.Final"]
     [org.clojure/data.json           "2.5.0"]
     [http.async.client               "1.3.0"] ; TODO Update (newer versions failing)
     [hato                            "0.9.0"]
     [compojure                       "1.7.1"]
     [org.clojure/tools.cli         "1.1.230"]]

    :plugins
    [[lein-pprint  "1.3.2"]
     [lein-ancient "0.7.0"]
     [com.taoensso.forks/lein-codox "0.10.11"]]}

   :nrepl
   {:plugins
    [[cider/cider-nrepl         "0.45.0"]
     [mx.cider/enrich-classpath "1.19.0"]]}}

  :aliases
  {"start-dev"       ["with-profile" "+dev,+nrepl" "repl" ":headless"]
   "test-no-ring-ws" ["with-profile" "+no-ring-websockets" "test" ":ci"]
   "test-ci"         ["do" ["test" ":ci"] ["test-no-ring-ws"]]})
