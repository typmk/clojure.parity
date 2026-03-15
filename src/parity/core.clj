(ns parity.core
  "Clojure cross-compiler parity toolkit.

  Four commands:
    init     reflect -> generate -> capture -> verify
    test     compare your results against reference
    status   dashboard + what to do next
    clear    remove generated files"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn- load-and-call [file & args]
  (binding [*command-line-args* (vec args)]
    (load-file file)))

(def results-dir "results")
(def expressions-file (str results-dir "/expressions.edn"))
(def reference-file (str results-dir "/reference.edn"))
(def results-file (str results-dir "/results.edn"))

(defn- file-exists? [path] (.exists (io/file path)))

;; =============================================================================
;; init
;; =============================================================================

(defn init
  "Reflect -> generate -> capture -> verify.

   Tiers:  --quick (~2k)  --balanced (~9k, default)  --thorough (~40k)
   Scope:  --lang (shipped only)  --contrib (contrib only)  [ns...] (specific)"
  [& args]
  (let [flags    (set (filter #(str/starts-with? % "--") args))
        ns-args  (remove #(str/starts-with? % "--") args)
        tier     (cond (flags "--quick")    "--quick"
                       (flags "--thorough") "--thorough"
                       :else                "--balanced")
        scope    (cond (flags "--lang")    "--lang"
                       (flags "--contrib") "--contrib"
                       :else               nil)
        sg-args  (cond-> [tier "--write" "lang/" "contrib/"]
                   scope   (conj scope)
                   (seq ns-args) (into ns-args))]
    (println (str "=== reflect + generate (" (subs tier 2)
                  (when scope (str ", " (subs scope 2)))
                  (when (seq ns-args) (str ", " (count ns-args) " namespaces"))
                  ") ==="))
    (apply load-and-call "src/parity/specgen.clj" sg-args)
    (println "\n=== expand ===")
    (load-and-call "src/parity/runner.clj" "expand")
    (println "\n=== capture ===")
    (load-and-call "src/parity/runner.clj" "capture")
    (println "\n=== verify ===")
    (let [ref (edn/read-string (slurp reference-file))
          n (count ref)
          ok (count (remove :error ref))]
      (println (format "  %d expressions, %d values, %d errors — reference OK" n ok (- n ok))))
    (println "\nReady. Ship expressions.edn to your target, eval each :expr,")
    (println "write results.edn, then run: par test results.edn")))

;; =============================================================================
;; test
;; =============================================================================

(defn test-impl
  "Compare target results against JVM reference."
  [file]
  (when-not (file-exists? reference-file)
    (println "No reference found. Run: par init")
    (System/exit 1))
  (when-not (file-exists? file)
    (println (str "File not found: " file))
    (System/exit 1))
  (load-and-call "src/parity/runner.clj" "test" file))

;; =============================================================================
;; status
;; =============================================================================

(defn status
  "Dashboard: coverage, pass/fail, what's next.
   --roadmap <src>   implementation priority from source analysis
   --reflect         JVM host contract summary"
  [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        positional (vec (remove #(str/starts-with? % "--") args))]

    (cond
      (not (file-exists? "lang/"))
      (do (println "No specs generated. Run: par init") (System/exit 0))

      (not (file-exists? reference-file))
      (do (println "Specs generated but no reference captured. Run: par init") (System/exit 0)))

    (let [ref (edn/read-string (slurp reference-file))
          exprs (edn/read-string (slurp expressions-file))
          n (count ref)
          values (count (remove :error ref))
          errors (count (filter :error ref))
          by-ns (group-by :ns exprs)]
      (println "=== PARITY STATUS ===")
      (println)
      (println (format "  Reference: %d expressions (%d values, %d expected errors)" n values errors))
      (println (format "  Namespaces: %d" (count by-ns)))
      (println)

      (if (file-exists? results-file)
        (let [target (edn/read-string (slurp results-file))
              tgt-by-expr (into {} (map (fn [r] [(:expr r) r]) target))
              ref-by-expr (into {} (map (fn [r] [(:expr r) r]) ref))
              pass (atom 0) fail (atom 0) error (atom 0) missing (atom 0)
              ns-pass (atom {}) ns-total (atom {})]
          (doseq [{:keys [eval ns]} exprs]
            (let [r (get ref-by-expr eval)
                  t (get tgt-by-expr eval)]
              (swap! ns-total update ns (fnil inc 0))
              (cond
                (nil? t)
                (swap! missing inc)

                (and (:error r) (:error t)
                     (= (:error-class r) (:error-class t)))
                (do (swap! pass inc) (swap! ns-pass update ns (fnil inc 0)))

                (and (not (:error r)) (not (:error t))
                     (= (:result r) (:result t)))
                (do (swap! pass inc) (swap! ns-pass update ns (fnil inc 0)))

                (:error t) (swap! error inc)
                :else      (swap! fail inc))))

          (let [total (+ @pass @fail @error @missing)
                pct (if (pos? total) (format "%.1f" (* 100.0 (/ (double @pass) total))) "0.0")]
            (println (format "  Results: %s/%d pass (%s%%)" @pass total pct))
            (println (format "           %d fail, %d error, %d missing" @fail @error @missing))
            (println)

            (println "  Per namespace:")
            (let [ns-data (for [ns-name (sort (keys @ns-total))]
                            {:ns ns-name
                             :total (get @ns-total ns-name 0)
                             :pass (get @ns-pass ns-name 0)})
                  complete (filter #(= (:pass %) (:total %)) ns-data)
                  incomplete (sort-by #(/ (double (:pass %)) (max 1 (:total %)))
                                      (remove #(= (:pass %) (:total %)) ns-data))]
              (doseq [{:keys [ns total pass]} incomplete]
                (println (format "    %-40s %4d/%4d (%s%%)"
                                 ns pass total
                                 (format "%.0f" (* 100.0 (/ (double pass) (max 1 total)))))))
              (when (seq complete)
                (println (format "\n    Complete: %d namespaces" (count complete)))
                (println (str "    " (str/join ", " (map :ns complete)))))

              (println)
              (println "  Next wins (most tests remaining):")
              (let [actionable (->> ns-data
                                    (filter #(< (:pass %) (:total %)))
                                    (sort-by #(- (:total %) (:pass %)) >))]
                (doseq [{:keys [ns total pass]} (take 5 actionable)]
                  (println (format "    %-40s %d remaining" ns (- total pass))))))))

        (println "  No results.edn found. Awaiting target implementation results."))

      ;; --roadmap
      (when (flags "--roadmap")
        (if-let [src (first positional)]
          (do (println "\n=== ROADMAP ===")
              (load-and-call "src/parity/analyze.clj" "roadmap" src))
          (println "\n  --roadmap requires: par status --roadmap <clojure-src>")))

      ;; --reflect
      (when (flags "--reflect")
        (println "\n=== JVM HOST CONTRACT ===")
        (load-and-call "src/parity/analyze.clj" "reflect")))))

;; =============================================================================
;; clear
;; =============================================================================

(defn clear
  "Remove generated files (lang/, contrib/, results/)."
  []
  (doseq [dir ["lang" "contrib" "results"]]
    (let [f (io/file dir)]
      (when (.exists f)
        (doseq [child (reverse (file-seq f))]
          (.delete child))
        (println (str "  removed " dir "/")))))
  (println "Clean."))

;; =============================================================================
;; CLI
;; =============================================================================

(defn usage []
  (println "
  par — Clojure cross-compiler parity toolkit

  par init [options]                 Reflect -> generate -> capture -> verify
    --quick                          ~2k expressions (happy path)
    --balanced                       ~9k expressions (default)
    --thorough                       ~40k expressions (full cross-product)
    --lang                           shipped Clojure namespaces only
    --contrib                        contrib libraries only
    [ns...]                          specific namespaces

  par test <results.edn>             Compare your implementation against reference

  par status                         Dashboard: coverage, pass/fail, what's next
    --roadmap <clojure-src>          include implementation priority order
    --reflect                        include JVM host contract

  par clear                          Remove generated files
"))

(defn -main [& args]
  (let [[cmd & cmd-args] args]
    (case cmd
      "init"   (apply init cmd-args)
      "test"   (if (first cmd-args)
                 (test-impl (first cmd-args))
                 (do (println "Usage: par test <results.edn>") (System/exit 1)))
      "status" (apply status cmd-args)
      "clear"  (clear)
      (usage))))

(apply -main *command-line-args*)
