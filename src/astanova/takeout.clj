(ns astanova.takeout
  "Main entry point for the takeout CLI tool.
   Delegates to babashka/cli dispatch."
  (:gen-class)
  (:require [babashka.cli :as bbcli]
            [astanova.cli :as cli]))

(defn -main
  "CLI entry point. Delegates to babashka/cli dispatch."
  [& args]
  (bbcli/dispatch cli/cli-tree args {:prog "takeout" :help true}))
