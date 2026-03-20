(ns parity.specgen
  "Auto-generate clojure.spec fdefs from JVM reflection + source + docstrings.

   Three signals:
     1. JVM reflection — arglists, type hints from metadata
     2. Source — core.clj defn forms, Java method signatures in RT.java
     3. Docstrings — 'Returns a map', 'coll must be sequential'

   Pipeline: reflect → parse-source → parse-docstring → merge → emit fdef"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.walk]
            [clojure.spec.alpha :as s]
            [parity.generate :as gen]))

;; =============================================================================
;; §1 — Docstring parsing
;; =============================================================================

(def ^:private return-patterns
  "Regex → spec for 'Returns ...' patterns in docstrings."
  [[#"(?i)returns?\s+(a\s+)?new\s+map"           'map?]
   [#"(?i)returns?\s+(a\s+)?map"                  'map?]
   [#"(?i)returns?\s+(a\s+)?new\s+vec"            'vector?]
   [#"(?i)returns?\s+(a\s+)?vec"                  'vector?]
   [#"(?i)returns?\s+(a\s+)?new\s+set"            'set?]
   [#"(?i)returns?\s+(a\s+)?set"                  'set?]
   [#"(?i)returns?\s+(a\s+)?seq"                  'seqable?]
   [#"(?i)returns?\s+(a\s+)?lazy\s+seq"           'seqable?]
   [#"(?i)returns?\s+(a\s+)?string"               'string?]
   [#"(?i)returns?\s+(a\s+)?keyword"              'keyword?]
   [#"(?i)returns?\s+(a\s+)?symbol"               'symbol?]
   [#"(?i)returns?\s+true"                        'boolean?]
   [#"(?i)returns?\s+nil"                         '(s/nilable any?)]
   [#"(?i)returns?\s+(the\s+)?number"             'number?]
   [#"(?i)returns?\s+(an?\s+)?int"                'int?]])

(def ^:private arg-constraint-patterns
  "Regex → spec for arg constraints in docstrings."
  [[#"(?i)must\s+be\s+(a\s+)?map"                'map?]
   [#"(?i)must\s+be\s+(a\s+)?seq"                'seqable?]
   [#"(?i)must\s+be\s+(a\s+)?coll"               'coll?]
   [#"(?i)must\s+be\s+(a\s+)?string"             'string?]
   [#"(?i)must\s+be\s+(a\s+)?number"             'number?]
   [#"(?i)must\s+be\s+(a\s+)?integer"            'int?]
   [#"(?i)must\s+be\s+(a\s+)?function"           'ifn?]
   [#"(?i)must\s+implement"                      'any?]
   [#"(?i)associative"                           'associative?]])

(defn parse-docstring-ret
  "Extract return type spec from a docstring. Returns a spec form or nil."
  [docstring]
  (when docstring
    (some (fn [[pattern spec]]
            (when (re-find pattern docstring) spec))
          return-patterns)))

(defn parse-docstring-constraints
  "Extract arg constraints from a docstring. Returns seq of spec forms."
  [docstring]
  (when docstring
    (keep (fn [[pattern spec]]
            (when (re-find pattern docstring) spec))
          arg-constraint-patterns)))

;; =============================================================================
;; §2 — Java source parsing
;; =============================================================================

(def ^:private java-type->spec
  "Map Java types from RT.java/Numbers.java to spec predicates."
  {"Object"                 'any?
   "int"                    'int?
   "long"                   'int?
   "double"                 'number?
   "float"                  'number?
   "boolean"                'boolean?
   "Number"                 'number?
   "String"                 'string?
   "CharSequence"           'string?
   "ISeq"                   '(s/nilable seqable?)
   "Seqable"                '(s/nilable seqable?)
   "IPersistentCollection"  '(s/nilable coll?)
   "IPersistentMap"         '(s/nilable map?)
   "Associative"            '(s/nilable associative?)
   "IPersistentVector"      '(s/nilable vector?)
   "IPersistentSet"         '(s/nilable set?)
   "IPersistentList"        '(s/nilable list?)
   "Sequential"             '(s/nilable sequential?)
   "Counted"                '(s/nilable counted?)
   "Indexed"                '(s/nilable (s/or :v vector? :s string?))
   "Keyword"                'keyword?
   "Symbol"                 'symbol?
   "IFn"                    'ifn?
   "AFunction"              'ifn?
   "Comparator"             'ifn?
   "Pattern"                'any?
   "Class"                  'any?
   "Var"                    'any?
   "Namespace"              'any?})

(def ^:private java-ret->spec
  "Map Java return types to spec predicates."
  {"Object"                 'any?
   "int"                    'int?
   "long"                   'int?
   "double"                 'number?
   "boolean"                'boolean?
   "Number"                 'number?
   "String"                 'string?
   "ISeq"                   '(s/nilable seqable?)
   "IPersistentCollection"  'coll?
   "IPersistentMap"         'map?
   "Associative"            'associative?
   "IPersistentVector"      'vector?
   "IPersistentSet"         'set?
   "void"                   'nil?})

(defn parse-java-methods
  "Parse static method signatures from a Java source file.
   Returns {method-name [{:ret type :args [{:name :type}]}]}."
  [path]
  (when (.exists (io/file path))
    (let [src (slurp path)
          ;; Match: static public RetType methodName(ArgType argName, ...)
          pattern #"static\s+public\s+(?:final\s+)?(\S+)\s+(\w+)\s*\(([^)]*)\)"
          matches (re-seq pattern src)]
      (reduce
        (fn [m [_ ret-type method-name args-str]]
          (let [args (when (and args-str (not (str/blank? args-str)))
                       (mapv (fn [arg]
                               (let [parts (str/split (str/trim arg) #"\s+")
                                     typ (first parts)
                                     ;; Strip array brackets, generics
                                     clean-type (-> typ (str/replace #"\[\]" "") (str/replace #"<.*>" "")
                                                    (str/replace #".*\." ""))]
                                 {:type clean-type :name (last parts)}))
                             (str/split args-str #",")))]
            (update m method-name (fnil conj []) {:ret ret-type :args (or args [])})))
        {}
        matches))))

(def ^:private clojure-src-base "/mnt/c/Proj/genera/ref/clojure/src")

(defn load-java-signatures
  "Load method signatures from RT.java, Numbers.java, and Util.java."
  []
  (merge-with into
    (parse-java-methods (str clojure-src-base "/jvm/clojure/lang/RT.java"))
    (parse-java-methods (str clojure-src-base "/jvm/clojure/lang/Numbers.java"))
    (parse-java-methods (str clojure-src-base "/jvm/clojure/lang/Util.java"))))

;; Mapping from clojure.core function names to Java method names in RT/Numbers
(def ^:private core->java
  "Map clojure.core function names to their Java implementation methods."
  {"assoc" "assoc" "dissoc" "dissoc" "get" "get" "contains?" "contains"
   "find" "find" "count" "count" "nth" "nth" "conj" "conj" "cons" "cons"
   "first" "first" "rest" "more" "next" "next" "seq" "seq"
   "keys" "keys" "vals" "vals" "peek" "peek" "pop" "pop"
   "meta" "meta" "with-meta" "with-meta"
   "+" "add" "-" "minus" "*" "multiply" "/" "divide"
   "+'" "addP" "-'" "minusP" "*'" "multiplyP"
   "inc" "inc" "dec" "dec" "inc'" "incP" "dec'" "decP"
   "<" "lt" "<=" "lte" ">" "gt" ">=" "gte" "==" "equiv"
   "zero?" "isZero" "pos?" "isPos" "neg?" "isNeg"
   "bit-and" "and" "bit-or" "or" "bit-xor" "xor" "bit-not" "not"
   "bit-shift-left" "shiftLeft" "bit-shift-right" "shiftRight"
   "compare" "compare" "hash" "hasheq"
   "str" "str" "name" "name" "namespace" "namespace"
   "keyword" "keyword" "symbol" "symbol"
   "int" "intCast" "long" "longCast" "float" "floatCast" "double" "doubleCast"
   "char" "charCast" "byte" "byteCast" "short" "shortCast" "boolean" "booleanCast"
   "not" "not" "list" "list" "vector" "vector" "hash-map" "map" "hash-set" "set"
   "subvec" "subvec"})

(defn java-arg-spec
  "Get spec for an arg based on its Java type. Returns spec form or nil."
  [java-sigs fn-name arg-idx]
  (when-let [java-name (core->java fn-name)]
    (when-let [overloads (get java-sigs java-name)]
      ;; Find the overload that matches the arity, or the first one
      (let [sig (or (first (filter #(> (count (:args %)) arg-idx) overloads))
                    (first overloads))]
        (when-let [arg (get (:args sig) arg-idx)]
          (get java-type->spec (:type arg)))))))

(defn java-ret-spec
  "Get return spec based on Java return type."
  [java-sigs fn-name]
  (when-let [java-name (core->java fn-name)]
    (when-let [overloads (get java-sigs java-name)]
      (let [ret-type (:ret (first overloads))]
        (get java-ret->spec ret-type)))))

;; =============================================================================
;; §3 — Type inference from multiple signals
;; =============================================================================

(def ^:private type->spec
  "Map parity's internal type keywords to spec predicates."
  {:num       'number?
   :string    'string?
   :keyword   'keyword?
   :symbol    'symbol?
   :coll      '(s/nilable seqable?)
   :vec       '(s/nilable vector?)
   :map       '(s/nilable map?)
   :set       '(s/nilable set?)
   :list      '(s/nilable list?)
   :fn        'ifn?
   :pred      'ifn?
   :regex     'any?
   :xf        'any?
   :char      'char?
   :any       'any?})

(def ^:private java-sigs-cache (atom nil))

(defn- get-java-sigs []
  (or @java-sigs-cache
      (let [sigs (try (load-java-signatures) (catch Exception _ {}))]
        (reset! java-sigs-cache sigs)
        sigs)))

(defn infer-arg-spec
  "Infer a spec for a single arg using all available signals.
   Priority: Java source > tag metadata > docstring > arg name."
  [arg ns-name docstring arg-idx fn-name]
  (let [;; Signal 1: Java source (strongest — actual types)
        java-spec (java-arg-spec (get-java-sigs) fn-name arg-idx)
        ;; Signal 2: tag metadata
        tag-type (gen/tag-type arg)
        ;; Signal 3: name-based inference (weakest)
        name-type (when (symbol? arg) (gen/arg-type arg ns-name))
        effective (or java-spec
                      (when (and tag-type (not= tag-type :skip))
                        (get type->spec tag-type))
                      (get type->spec name-type)
                      'any?)]
    effective))

(defn infer-ret-spec
  "Infer return type spec from Java source, docstring, and function name.
   Priority: Java source > docstring > name convention."
  [sym docstring]
  (or (java-ret-spec (get-java-sigs) (name sym))
      (parse-docstring-ret docstring)
      (when (str/ends-with? (name sym) "?") 'boolean?)
      'any?))

;; =============================================================================
;; §3 — fdef generation
;; =============================================================================

(defn gen-fdef
  "Generate an fdef form for a function from its metadata.
   Returns {:sym symbol :fdef-form list} or nil."
  [sym var-meta ns-name]
  (let [arglists (:arglists var-meta)
        docstring (:doc var-meta)
        qualified (symbol ns-name (name sym))]
    (when (and arglists (= :testable (gen/classify-var sym var-meta)))
      (let [;; Build args spec from arglists
            arity-specs
            (->> arglists
                 (map (fn [arglist]
                        (let [fixed (vec (take-while #(not= '& %) arglist))
                              has-rest? (some #{'&} arglist)
                              arg-specs (vec (map-indexed
                                              (fn [i a]
                                                (let [spec (infer-arg-spec a ns-name docstring i (name sym))
                                                      arg-name (if (symbol? a) (keyword (name a)) :arg)]
                                                  [arg-name spec]))
                                              fixed))]
                          {:fixed arg-specs :variadic? has-rest? :arity (count fixed)})))
                 (sort-by :arity)
                 vec)
            ;; Dedupe by arity count
            deduped (reduce (fn [acc {:keys [arity] :as spec}]
                              (if (some #(= arity (:arity %)) acc)
                                acc (conj acc spec)))
                            [] arity-specs)
            ;; Build s/alt or s/cat for args
            args-spec
            (if (= 1 (count deduped))
              (let [{:keys [fixed variadic?]} (first deduped)]
                (if variadic?
                  (list* 's/cat (concat (mapcat identity fixed) [:more (list 's/* 'any?)]))
                  (list* 's/cat (mapcat identity fixed))))
              ;; Multiple arities → s/alt
              (list* 's/alt
                     (mapcat (fn [{:keys [fixed arity]}]
                               [(keyword (str "arity-" arity))
                                (list* 's/cat (mapcat identity fixed))])
                             deduped)))
            ;; Return spec from docstring
            ret-spec (infer-ret-spec sym docstring)]
        {:sym qualified
         :fdef-form (list 's/fdef qualified :args args-spec :ret ret-spec)}))))

;; =============================================================================
;; §4 — Bulk generation + validation
;; =============================================================================

(defn gen-ns-fdefs
  "Generate fdefs for all testable functions in a namespace."
  [ns-name]
  (require (symbol ns-name))
  (let [publics (sort-by key (ns-publics (symbol ns-name)))]
    (->> publics
         (keep (fn [[sym v]]
                 (try
                   (gen-fdef sym (meta v) ns-name)
                   (catch Exception _ nil))))
         vec)))

(defn resolve-spec-form
  "Convert a spec form with 's/cat symbols to evaluable 'clojure.spec.alpha/cat."
  [form]
  (clojure.walk/postwalk
    (fn [x]
      (if (and (symbol? x) (= "s" (namespace x)))
        (symbol "clojure.spec.alpha" (name x))
        x))
    form))

(defn validate-fdef
  "Validate a generated fdef by trying to generate args and eval.
   Returns {:sym :valid? :error}."
  [{:keys [sym fdef-form]}]
  (try
    (eval (resolve-spec-form fdef-form))
    (let [spec (s/get-spec sym)
          args-spec (:args spec)]
      (when args-spec
        (let [samples (s/exercise args-spec 5)]
          {:sym sym :valid? true :samples (count samples)})))
    (catch Exception e
      {:sym sym :valid? false :error (.getMessage e)})))

(defn emit-specs-file
  "Generate and write fdefs for a namespace to a file."
  [ns-name output-path]
  (let [fdefs (gen-ns-fdefs ns-name)
        valid (mapv validate-fdef fdefs)
        good (filter :valid? valid)
        bad (remove :valid? valid)]
    (with-open [w (io/writer output-path)]
      (.write w (str "(ns parity.specs." (str/replace ns-name "." "-") "\n"
                     "  \"Auto-generated specs for " ns-name " — generated by parity.specgen\"\n"
                     "  (:require [clojure.spec.alpha :as s]))\n\n"))
      (doseq [{:keys [sym fdef-form]} fdefs
              :when (:valid? (first (filter #(= sym (:sym %)) valid)))]
        (.write w (str (pr-str fdef-form) "\n"))))
    (println (str "  " ns-name ": " (count good) " valid, " (count bad) " failed → " output-path))
    {:ns ns-name :total (count fdefs) :valid (count good) :failed (count bad)
     :failures (mapv #(select-keys % [:sym :error]) bad)}))

(defn -main
  "Generate specs for clojure.core and optionally other namespaces."
  [& args]
  (let [nses (or (seq args) ["clojure.core"])]
    (doseq [ns-name nses]
      (let [out (str "src/parity/specs/" (str/replace ns-name "." "_") ".clj")]
        (io/make-parents out)
        (emit-specs-file ns-name out)))))
