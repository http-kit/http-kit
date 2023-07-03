(ns build
  (:require [babashka.process :refer [shell]]))

(set! *warn-on-reflection* true)
(defn lein-javac [_] (shell "lein javac"))
