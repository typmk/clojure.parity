(ns parity.generate
  "Generate parity reference: reflect -> specs -> expand -> capture -> verify.

  Reads spec .edn files from lang/ and contrib/, expands parametric templates
  into concrete expressions, evaluates each on JVM, writes reference answers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.walk]
            [clojure.data]
            [clojure.pprint]
            [clojure.math]
            [clojure.core.reducers]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async]))

(def base-dir (or (System/getProperty "parity.dir") "."))
(def spec-dirs [(str base-dir "/lang") (str base-dir "/contrib")])
(def results-dir (str base-dir "/results"))
(def expressions-file (str results-dir "/expressions.edn"))
(def reference-file (str results-dir "/reference.edn"))

;; =============================================================================
;; Expand — spec files -> flat expression list
;; =============================================================================

(defn spec-files []
  (->> spec-dirs
       (mapcat #(file-seq (io/file %)))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (sort-by #(.getName %))
       (map #(.getPath %))))

(defn cross-product [params]
  (reduce
    (fn [combos [k vals]]
      (for [combo combos, v vals]
        (assoc combo k v)))
    [{}]
    (map vector (keys params) (vals params))))

(defn substitute [template bindings]
  (reduce-kv
    (fn [s k v] (str/replace s (str "%" (name k)) v))
    template bindings))

(defn expand-parametric [{:keys [describe params template]}]
  (mapv (fn [bindings]
          {:it (str describe " " (str/join " " (vals bindings)))
           :eval (substitute template bindings)
           :source :parametric})
        (cross-product params)))

(defn expand-category [ns-prefix {:keys [category tests parametric]}]
  (let [full-cat (if ns-prefix
                   (keyword (str ns-prefix "." (name category)))
                   category)]
    (into
      (mapv #(assoc % :category full-cat :ns ns-prefix :source :explicit) tests)
      (mapcat (fn [p]
                (map #(assoc % :category full-cat :ns ns-prefix) (expand-parametric p)))
              parametric))))

(defn expand-spec-file [spec-path]
  (let [prefix (-> (io/file spec-path) .getName (str/replace #"\.edn$" ""))
        specs (edn/read-string (slurp spec-path))]
    (vec (mapcat (partial expand-category prefix) specs))))

(defn expand []
  (let [files (spec-files)
        expressions (vec (mapcat expand-spec-file files))
        by-ns (group-by :ns expressions)]
    (io/make-parents expressions-file)
    (spit expressions-file (pr-str expressions))
    (println (format "Expanded %d expressions from %d spec files" (count expressions) (count files)))
    (doseq [[ns-name ns-tests] (sort-by first by-ns)]
      (println (format "  %-40s %d" ns-name (count ns-tests))))
    expressions))

;; =============================================================================
;; Capture — eval expressions on JVM, save reference
;; =============================================================================

(def ^:dynamic *eval-timeout-ms* 1000)
(def ^:dynamic *capture-threads* (.availableProcessors (Runtime/getRuntime)))

(defn safe-eval [expr-str]
  (let [result (promise)
        t (Thread.
            (fn []
              (deliver result
                (try
                  (binding [*print-length* 100
                            *print-level* 10
                            *out* (java.io.StringWriter.)
                            *err* (java.io.StringWriter.)]
                    (let [form (read-string expr-str)
                          val (eval form)
                          s (pr-str val)]
                      {:result s :type (str (type val))}))
                  (catch Throwable t
                    {:error (str (.getSimpleName (class t)) ": " (.getMessage t))
                     :error-class (str (.getSimpleName (class t)))})))))]
    (.setDaemon t true)
    (.start t)
    (let [r (deref result *eval-timeout-ms* ::timeout)]
      (if (= r ::timeout)
        (do (.interrupt t)
            {:error "TimeoutException: eval exceeded timeout"
             :error-class "TimeoutException"})
        r))))

(defn capture []
  (let [expressions (expand)
        total (count expressions)
        n-threads *capture-threads*
        errors (atom 0)
        done (atom 0)
        start (System/currentTimeMillis)
        chunks (partition-all (max 1 (quot total n-threads)) expressions)
        eval-chunk (fn [exprs]
                     (mapv (fn [{:keys [it eval category]}]
                             (let [result (safe-eval eval)
                                   entry (merge {:expr eval :category category :it it} result)
                                   n (swap! done inc)]
                               (when (:error entry) (swap! errors inc))
                               (when (zero? (mod n 1000))
                                 (binding [*out* *err*]
                                   (println (format "  %d/%d (%.1fs)" n total
                                                    (/ (- (System/currentTimeMillis) start) 1000.0)))))
                               entry))
                           exprs))
        futures (mapv #(future (eval-chunk %)) chunks)]
    (println (format "Capturing %d expressions (%d threads)..." total n-threads))
    (let [results (vec (mapcat deref futures))
          elapsed (/ (- (System/currentTimeMillis) start) 1000.0)]
      (io/make-parents reference-file)
      (spit reference-file (with-out-str
                             (println "[")
                             (doseq [r results] (prn r))
                             (println "]")))
      (println (format "  %d results in %.1fs (%d values, %d errors)"
                       total elapsed (- total @errors) @errors))
      results)))

(defn verify []
  (let [ref (edn/read-string (slurp reference-file))
        n (count ref)
        ok (count (remove :error ref))]
    (println (format "  %d expressions, %d values, %d errors — reference OK" n ok (- n ok)))))

;; =============================================================================
;; Main (called by core.clj via load-and-call)
;; =============================================================================

(defn -main [& args]
  (case (first args)
    "expand"  (expand)
    "capture" (capture)
    "verify"  (verify)
    (do (println "Usage: generate <expand|capture|verify>")
        (System/exit 1))))

(apply -main *command-line-args*)
