(defproject debug-sse "0.1.0-SNAPSHOT"
  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [http-kit/http-kit "2.9.0-SNAPSHOT"]]

  :main debug-sse.core

  :repl-options {:init-ns debug-sse.core})
