(ns astanova.takeout
  "Main entry point for the takeout CLI tool.
   Delegates to astanova.cli/dispatch for argument parsing and command execution."
  (:gen-class)
  (:require [astanova.cli :as cli]))

(defn -main
  "CLI entry point. Delegates to babashka/cli dispatch."
  [& args]
  (cli/dispatch cli/cli-tree args {:prog "takeout" :help true}))
