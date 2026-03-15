(ns parity.analyze
  "Analysis coordinator: reflect, deps, roadmap.

  Delegates to langmap (JVM reflection), depgraph (source analysis),
  and tree (merge + prioritize). This file is the entry point —
  langmap/depgraph/tree are implementation modules."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn- load-and-call [file & args]
  (binding [*command-line-args* (vec args)]
    (load-file file)))

(defn reflect
  "JVM host contract: what interfaces, methods, types Clojure requires."
  [& args]
  (apply load-and-call "src/parity/langmap.clj" (or args [])))

(defn deps
  "Source-level dependency graph from Clojure .clj files."
  [& args]
  (apply load-and-call "src/parity/depgraph.clj" args))

(defn roadmap
  "What to implement next. Merges source deps + JVM host contract."
  [clojure-src & args]
  (let [tmp-graph (java.io.File/createTempFile "parity-graph" ".edn")
        tmp-host  (java.io.File/createTempFile "parity-host" ".edn")]
    (try
      (spit tmp-graph (with-out-str
                        (load-and-call "src/parity/depgraph.clj" clojure-src "--edn")))
      (spit tmp-host (with-out-str
                       (load-and-call "src/parity/langmap.clj" "--edn")))
      (apply load-and-call "src/parity/tree.clj"
             (str tmp-graph) (str tmp-host) args)
      (finally
        (.delete tmp-graph)
        (.delete tmp-host)))))

(defn -main [& args]
  (let [[cmd & cmd-args] args]
    (case cmd
      "reflect" (apply reflect cmd-args)
      "deps"    (apply deps cmd-args)
      "roadmap" (apply roadmap cmd-args)
      (do (println "Usage: analyze <reflect|deps|roadmap> [args...]")
          (System/exit 1)))))

(apply -main *command-line-args*)
