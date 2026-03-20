(ns parity.generate
  "Reflect on JVM, generate tests, capture reference, emit cljc."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [parity.specs :as specs]))

;; Load all namespaces for reflection
(def shipped-namespaces
  '[clojure.set clojure.walk clojure.data clojure.datafy clojure.edn
    clojure.math clojure.repl clojure.test clojure.zip clojure.xml
    clojure.stacktrace clojure.template clojure.instant clojure.reflect
    clojure.main clojure.java.browse clojure.java.javadoc clojure.java.process
    clojure.java.shell clojure.core.reducers clojure.core.protocols
    clojure.core.server clojure.spec.alpha clojure.spec.gen.alpha
    clojure.spec.test.alpha])

(def contrib-namespaces
  '[clojure.core.async clojure.core.cache clojure.core.memoize
    clojure.data.csv clojure.data.json clojure.data.xml clojure.data.zip
    clojure.data.priority-map clojure.data.int-map clojure.data.avl
    clojure.data.codec.base64 clojure.java.data clojure.java.classpath
    clojure.math.combinatorics clojure.math.numeric-tower
    clojure.test.check clojure.test.check.generators
    clojure.test.check.properties clojure.test.check.clojure-test
    clojure.tools.cli clojure.tools.logging clojure.tools.namespace.find
    clojure.tools.namespace.repl clojure.tools.reader clojure.tools.reader.edn
    clojure.tools.trace clojure.algo.generic.arithmetic
    clojure.algo.generic.collection clojure.algo.generic.functor
    clojure.algo.generic.math-functions clojure.algo.monads
    clojure.core.logic clojure.core.match clojure.core.unify])

;; Lazy — only load namespaces when needed (contrib is heavy)
(defn load-namespaces!
  "Require namespaces for the given scope (:lang, :contrib, or :all)."
  [scope]
  (let [nss (case scope
              :lang shipped-namespaces
              :contrib contrib-namespaces
              :all (concat shipped-namespaces contrib-namespaces))]
    (doseq [ns-sym nss]
      (try (require ns-sym) (catch Exception _)))))

(def default-namespaces
  (vec (map str (cons 'clojure.core shipped-namespaces))))

(def all-namespaces
  (vec (concat default-namespaces (map str contrib-namespaces))))

;; =============================================================================
;; Type-driven test values — one good value per type, plus nil and empty
;; =============================================================================

(def values
  "Test values per type. :good is the primary, :alts are additional values to exercise,
   :nil and :empty are boundary values for nilable types."
  {:num     {:good "42"        :alts ["-1" "0" "1" "0.5"]
             :nil "nil"        :empty "0"}
   :string  {:good "\"hello\"" :alts ["\"\"" "\"a\"" "\"hello world\""]
             :nil "nil"        :empty "\"\""}
   :keyword {:good ":a"        :alts [":b" ":foo/bar"]
             :nil "nil"}
   :symbol  {:good "'foo"      :alts ["'bar" "'foo/baz"]
             :nil "nil"}
   :coll    {:good "[1 2 3]"   :alts ["'(1 2 3)" "[\"a\" :b 3]" "[[1] [2]]"]
             :nil "nil"        :empty "[]"}
   :vec     {:good "[1 2 3]"   :alts ["[0]" "[:a :b :c :d :e]"]
             :nil "nil"        :empty "[]"}
   :map     {:good "{:a 1}"    :alts ["{:a 1 :b 2}" "{0 \"x\" 1 \"y\"}"]
             :nil "nil"        :empty "{}"}
   :set     {:good "#{1 2 3}"  :alts ["#{:a :b}" "#{}"]
             :nil "nil"        :empty "#{}"}
   :list    {:good "'(1 2 3)"  :alts ["'()" "'(:a :b)"]
             :nil "nil"        :empty "'()"}
   :fn      {:good "inc"       :alts ["dec" "str" "identity"]}
   :pred    {:good "even?"     :alts ["odd?" "nil?" "pos?"]}
   :regex   {:good "#\"\\\\d+\""}
   :xf      {:good "(map inc)" :alts ["(filter odd?)" "(take 2)"]}
   :char    {:good "\\a"       :alts ["\\z" "\\space"]}
   :any     {:good "42"        :alts [":a" "\"hello\"" "nil" "true" "false" "[1 2]" "{:a 1}"]
             :nil "nil"        :empty "[]"}})

(def tag->type
  "Map JVM type hints to our value types. nil = untestable (skip the var)."
  {"String" :string "CharSequence" :string "Writer" :string
   "Number" :num "long" :num "int" :num "double" :num "float" :num "short" :num "byte" :num
   "Boolean" :pred "boolean" :pred
   "Object" :any
   "Class" nil
   "clojure.lang.IFn" :fn "clojure.lang.AFunction" :fn
   "clojure.lang.IPersistentMap" :map "clojure.lang.Associative" :map
   "clojure.lang.IPersistentVector" :vec
   "clojure.lang.IPersistentSet" :set "clojure.lang.IPersistentCollection" :coll
   "clojure.lang.ISeq" :coll "clojure.lang.Seqable" :coll
   "clojure.lang.Sequential" :coll "clojure.lang.Counted" :coll
   "clojure.lang.Indexed" :vec "clojure.lang.Reversible" :vec
   "clojure.lang.Symbol" :symbol "clojure.lang.Named" :keyword
   "clojure.lang.Keyword" :keyword
   "clojure.lang.IObj" :any "clojure.lang.IMeta" :any
   "java.util.regex.Pattern" :regex "java.util.regex.Matcher" :regex
   "java.util.Comparator" :fn "java.util.Collection" :coll
   "java.util.Map$Entry" nil
   ;; Untestable with simple values — skip
   "clojure.lang.Agent" nil "clojure.lang.Ref" nil "clojure.lang.Var" nil
   "clojure.lang.IRef" nil "clojure.lang.IReference" nil
   "clojure.lang.IAtom" nil "clojure.lang.IAtom2" nil
   "clojure.lang.Volatile" nil "clojure.lang.IPending" nil
   "clojure.lang.MultiFn" nil "clojure.lang.Sorted" nil
   "clojure.lang.IEditableCollection" nil
   "clojure.lang.ITransientCollection" nil "clojure.lang.ITransientMap" nil
   "clojure.lang.ITransientVector" nil "clojure.lang.ITransientSet" nil
   "clojure.lang.ITransientAssociative" nil
   "clojure.lang.IChunkedSeq" nil "clojure.lang.ChunkBuffer" nil
   "clojure.lang.LineNumberingPushbackReader" nil
   "IProxy" nil "StackTraceElement" nil "Throwable" nil
   "java.util.concurrent.Future" nil "java.util.stream.BaseStream" nil
   "java.lang.reflect.Method" nil "java.io.BufferedReader" nil
   "java.sql.ResultSet" nil})

(defn tag-type
  "Get value type from arglist tag metadata. Returns :skip for untestable types."
  [arg]
  (when-let [tag (:tag (meta arg))]
    (let [t (str tag)]
      (if (contains? tag->type t)
        (let [v (get tag->type t)]
          (if (nil? v) :skip v))
        ;; Unknown tag — if it's a clojure.lang or java type, skip
        (when (or (str/starts-with? t "clojure.lang.")
                  (str/starts-with? t "java."))
          :skip)))))

(defn arg-type
  "Infer value type from tag metadata, then arg name, then namespace context."
  [arg-name ns-name]
  (let [s (str arg-name)]
    (cond
      (= s "&")                                          nil
      (#{"n" "num" "x" "y" "a" "b" "start" "end"
         "step" "init" "from" "to" "index" "i" "j"
         "min" "max" "cnt" "len" "size" "depth"
         "dividend" "divisor" "base" "exp" "val"
         "low" "high" "p" "q" "d" "r"} s)               :num
      (#{"s" "string" "cs" "substr" "replacement"
         "input" "output" "line" "msg" "message"
         "prefix" "suffix" "sep" "separator"
         "format" "fmt" "uri" "url" "path"
         "filename" "encoding" "name" "attr"} s)         :string
      (#{"coll" "c" "c1" "c2" "c3" "colls"
         "xs" "ys" "items" "l" "list" "seq"} s)          :coll
      (#{"f" "fn" "pred" "g" "func" "action"
         "handler" "callback"} s)                        :fn
      (#{"xform" "xf"} s)                                :xf
      (#{"k" "key" "t" "tag" "type"} s)                  :keyword
      (#{"v" "e"} s)                                     :any
      (#{"m" "map" "kmap" "smap" "opts" "options"
         "bindings" "env" "params" "args"} s)            :map
      (#{"re" "pattern" "match"} s)                      :regex
      (#{"xrel" "yrel" "xset" "s1" "s2"} s)             :set
      (#{"ch"} s)                                        :char
      (str/ends-with? s "map")                           :map
      (str/ends-with? s "set")                           :set
      (str/ends-with? s "s")                             :coll
      (str/ends-with? s "?")                             :any
      (= ns-name "clojure.string")                       :string
      (= ns-name "clojure.math")                         :num
      (= ns-name "clojure.set")                          :set
      :else                                              :any)))

(def nilable-types
  "Types where nil/empty are meaningful inputs (not just error-producing)."
  #{:coll :vec :map :set :list :string})

(defn effective-type
  "Get the effective type for an arg: tag metadata first, then name-based inference."
  [arg ns-name]
  (or (tag-type arg) (arg-type arg ns-name)))

(defn test-values-for
  "Generate test value sets for an arg: [{:label suffix :val string}]."
  [arg ns-name]
  (let [typ (effective-type arg ns-name)
        v (get values typ (:any values))]
    (cond-> [{:label (str arg) :val (:good v)}]
      (and (:nil v) (nilable-types typ))     (conj {:label (str arg "=nil") :val "nil"})
      (and (:empty v) (nilable-types typ))   (conj {:label (str arg "=empty") :val (:empty v)}))))

;; =============================================================================
;; Var classification
;; =============================================================================

(def skip-vars
  "Vars that can't be tested with simple expression eval."
  (into #{}
    (mapcat identity
      [;; Dynamic vars
       '[*ns* *out* *err* *in* *file* *command-line-args* *print-length*
         *print-level* *print-meta* *print-dup* *print-readably* *flush-on-newline*
         *read-eval* *data-readers* *default-data-reader-fn* *assert*
         *math-context* *agent* *1 *2 *3 *e *warn-on-reflection*
         *unchecked-math* *compiler-options* *compile-path* *compile-files*
         *allow-unresolved-vars* *reader-resolver* *source-path*
         *use-context-classloader* *verbose-defrecords* *fn-loader* *suppress-read*]
       ;; Side effects / destructive
       '[shutdown-agents alter-var-root intern ns-unmap ns-unalias remove-ns
         in-ns load load-file load-reader load-string require use import
         refer refer-clojure compile gen-class gen-interface]
       ;; Need context (agent, ref, atom, volatile, promise, dosync)
       '[send send-off send-via restart-agent await await-for await1
         add-watch remove-watch set-validator! dosync commute alter ref-set
         ensure swap! reset! swap-vals! reset-vals! vswap! vreset! deliver]
       ;; Async / IO
       '[future future-call pmap pcalls pvalues locking io!
         slurp spit print println pr prn printf newline flush
         read read-line read-string]
       ;; Test runners (side effects, run all loaded tests)
       '[run-tests run-all-tests run-test run-test-var test-var test-vars
         test-ns test-all-vars do-report inc-report-counter
         set-test with-test deftest deftest- testing is are try-expr
         assert-any assert-predicate use-fixtures]
       ;; Need hierarchy / eval context
       '[class type supers bases parents ancestors descendants
         make-hierarchy isa? derive underive
         eval macroexpand macroexpand-1 special-symbol? find-keyword]
       ;; Binding forms
       '[binding with-bindings with-bindings* with-redefs with-redefs-fn]
       ;; Java arrays / interop (need Java types, not Clojure values)
       '[aclone alength aget aset amap areduce into-array to-array to-array-2d
         make-array boolean-array byte-array char-array short-array int-array
         long-array float-array double-array object-array booleans bytes chars
         shorts ints longs floats doubles
         aset-boolean aset-byte aset-char aset-double aset-float aset-int aset-long aset-short
         .. proxy proxy-super bean add-classpath]
       ;; Need specific JVM objects
       '[StackTraceElement->vec Throwable->map -cache-protocol-fn
         accessor agent-error agent-errors create-struct struct struct-map
         set-agent-send-executor! set-agent-send-off-executor!
         clear-agent-errors error-handler error-mode set-error-handler!
         set-error-mode! release-pending-sends
         alias ns-name ns-publics ns-map ns-imports ns-interns ns-refers
         ns-aliases ns-resolve the-ns find-ns create-ns ns-loaded?
         alter-meta! reset-meta! cast denominator numerator
         proxy-call-with-super]
       ;; I/O and files (need real files/streams)
       '[file-seq line-seq xml-seq parse
         startparse-sax source source-fn
         pst root-cause stack-element-str]
       ;; Opens browser / external programs
       '[javadoc browse-url open-url-in-browser open-url-in-swing]])))

(defn classify-var
  "Classify a var as :testable or :skip based on name, arglists, and skip-list."
  [sym var-meta]
  (let [nm (name sym)
        arglists (:arglists var-meta)]
    (cond
      (skip-vars sym)                 :skip
      (nil? arglists)                 :skip
      (str/starts-with? nm "->")      :skip
      (str/starts-with? nm "map->")   :skip
      (> (apply min (map #(count (take-while (fn [a] (not= '& a)) %)) arglists)) 6) :skip
      :else                           :testable)))

;; =============================================================================
;; Test generation — intent-driven, not brute-force
;; =============================================================================

(defn arg-name-str
  "Coerce an arglist element to a string name."
  [arg]
  (cond (symbol? arg) (name arg) (vector? arg) "v" (map? arg) "m" :else "x"))

(defn- make-test [sym qualified args]
  {:it (str (name sym) " " (str/join " " args))
   :eval (str "(" qualified " " (str/join " " args) ")")})

(defn- gen-arity-tests
  "Generate tests for a specific fixed-arg vector: happy path + nil/empty per arg position."
  [sym qualified fixed-args ns-name]
  (let [happy-args (mapv (fn [a] (:good (get values (effective-type a ns-name) (:any values)))) fixed-args)]
    (reduce
      (fn [acc i]
        (let [typ (effective-type (nth fixed-args i) ns-name)
              v (get values typ)]
          (cond-> acc
            (and (:nil v) (nilable-types typ))
            (conj (make-test sym qualified (assoc happy-args i "nil")))
            (and (:empty v) (nilable-types typ))
            (conj (make-test sym qualified (assoc happy-args i (:empty v)))))))
      [(make-test sym qualified happy-args)]
      (range (count fixed-args)))))

(defn- gen-alt-tests
  "Generate tests using :alts values for each arg position (one alt at a time)."
  [sym qualified fixed-args ns-name]
  (let [happy-args (mapv (fn [a] (:good (get values (effective-type a ns-name) (:any values)))) fixed-args)]
    (into []
          (mapcat
            (fn [i]
              (let [typ (effective-type (nth fixed-args i) ns-name)
                    alts (:alts (get values typ))]
                (when alts
                  (map (fn [alt] (make-test sym qualified (assoc happy-args i alt))) alts)))))
          (range (count fixed-args)))))

(defn gen-tests
  "Generate tests for a single var. Returns seq of {:it :eval} maps.
   Tests each distinct arity, with nil/empty/alts per arg position."
  [sym var-meta ns-name]
  (let [arglists (:arglists var-meta)
        qualified (if (= ns-name "clojure.core") (name sym) (str ns-name "/" (name sym)))
        has-nullary? (some #(zero? (count %)) arglists)
        testable-arities (->> arglists
                              (map (fn [al] (vec (take-while #(not= '& %) al))))
                              (filter #(<= 1 (count %) 6))
                              (remove (fn [args] (some #{:skip} (mapv tag-type args))))
                              (reduce (fn [acc args]
                                        (if (some #(= (count %) (count args)) acc)
                                          acc (conj acc args)))
                                      []))
        best-arity (first testable-arities)
        arity (if best-arity (count best-arity) 0)
        is-pred? (and (str/ends-with? (name sym) "?") (= 1 arity))]
    (when (or has-nullary? (seq testable-arities) is-pred?)
      (cond->
        []
        ;; Nullary
        has-nullary?
        (conj {:it (name sym) :eval (str "(" qualified ")")})

        ;; Predicate: test against representative types
        is-pred?
        (into (for [v ["nil" "true" "false" "0" "42" "-1" "0.5"
                       "\"\"" "\"hello\"" ":a" "'foo"
                       "[]" "[1 2]" "{}" "{:a 1}" "#{}" "#{1}" "'()"]]
                {:it (str (name sym) " " v) :eval (str "(" qualified " " v ")")}))

        ;; Normal function: test each arity with happy/nil/empty/alts per position
        (and (not is-pred?) (seq testable-arities))
        (into (mapcat (fn [args]
                        (concat (gen-arity-tests sym qualified args ns-name)
                                (gen-alt-tests sym qualified args ns-name)))
                      testable-arities))

        ;; Spec-generated tests (if fdef exists, add diverse inputs)
        (try
          (specs/spec-available? (symbol ns-name (name sym)))
          (catch Exception _ false))
        (into (try
                (let [fq (symbol ns-name (name sym))
                      samples (specs/gen-from-spec fq :n 10)]
                  (when samples
                    (->> samples
                         (map (fn [args] (specs/args->expr-str fq args)))
                         distinct
                         (map (fn [expr] {:it (str (name sym) " [spec]")
                                          :eval expr})))))
                (catch Exception _ nil)))))))


;; =============================================================================
;; Namespace processing
;; =============================================================================

(defn process-namespace
  "Reflect on a namespace, generate tests for all testable public vars."
  [ns-name]
  (try
    (require (symbol ns-name))
    (let [publics (sort-by key (ns-publics (symbol ns-name)))
          tests (vec (mapcat (fn [[sym v]]
                               (when (= :testable (classify-var sym (meta v)))
                                 (try (gen-tests sym (meta v) ns-name)
                                      (catch Exception e
                                        (binding [*out* *err*]
                                          (println (str "WARNING: gen-tests failed for " sym ": " (.getMessage e))))
                                        nil))))
                             publics))]
      {:ns ns-name :total (count publics) :tests tests
       :generated (count tests) :skipped (- (count publics) (count (filter #(= :testable (classify-var (key %) (meta (val %)))) publics)))})
    (catch Exception e
      {:ns ns-name :total 0 :tests [] :generated 0 :skipped 0 :error (str e)})))

;; =============================================================================
;; Output — write spec .edn files
;; =============================================================================

(defn write-specs
  "Write per-namespace spec .edn files to lang and contrib directories."
  [results lang-dir contrib-dir]
  (let [contrib-set (set (map str contrib-namespaces))]
    (doseq [{:keys [ns tests]} results :when (seq tests)]
      (let [dir (if (contrib-set ns) contrib-dir lang-dir)
            path (str dir "/" ns ".edn")]
        (io/make-parents path)
        (spit path (pr-str [{:category (keyword ns) :tests tests}]))
        (println (format "  %-45s %d tests" path (count tests)))))))

(defn print-stats
  "Print generation summary: namespaces, vars, tests, skipped."
  [results]
  (let [total-tests (reduce + (map :generated results))
        total-vars (reduce + (map :total results))]
    (println (format "\n  %d namespaces, %d vars, %d tests generated\n"
                     (count results) total-vars total-tests))
    (doseq [{:keys [ns total generated skipped error]} results]
      (if error
        (println (format "  %-45s ERROR: %s" ns error))
        (println (format "  %-45s %4d vars  %4d tests  %4d skipped"
                         ns total generated skipped))))))

;; =============================================================================
;; Expand — spec files -> flat expression list
;; =============================================================================

(def base-dir (or (System/getProperty "parity.dir") "."))
(def spec-dirs [(str base-dir "/lang") (str base-dir "/contrib")])
(def results-dir (str base-dir "/results"))
(def expressions-file (str results-dir "/expressions.edn"))
(def reference-file (str results-dir "/reference.edn"))

(defn spec-files
  "Find all .edn spec files in lang/ and contrib/ directories."
  []
  (->> spec-dirs
       (mapcat #(file-seq (io/file %)))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (sort-by #(.getName %))
       (map #(.getPath %))))

(defn expand-spec-file
  "Expand a single spec .edn file into a flat list of test expressions."
  [path]
  (let [ns-prefix (-> (io/file path) .getName (str/replace #"\.edn$" ""))
        specs (edn/read-string (slurp path))]
    (vec (mapcat (fn [{:keys [category tests]}]
                   (mapv #(assoc % :category category :ns ns-prefix) tests))
                 specs))))

(defn do-expand
  "Expand all spec files into a single expressions.edn."
  []
  (let [files (spec-files)
        expressions (vec (mapcat expand-spec-file files))]
    (io/make-parents expressions-file)
    (spit expressions-file (pr-str expressions))
    (println (format "  %d expressions from %d spec files" (count expressions) (count files)))
    expressions))

;; =============================================================================
;; Capture — parallel eval on JVM
;; =============================================================================

(def ^:dynamic *eval-timeout-ms* 1000)
(def ^:dynamic *capture-threads* (.availableProcessors (Runtime/getRuntime)))

(defn safe-eval
  "Eval an expression string with timeout, returning {:result ...} or {:error ...}."
  [expr-str]
  (let [result (promise)
        t (Thread.
            (fn []
              (deliver result
                (try
                  (binding [*print-length* 100 *print-level* 10
                            *out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
                    (let [v (eval (read-string expr-str))]
                      {:result (pr-str v) :type (str (type v))}))
                  (catch Throwable t
                    {:error (str (.getSimpleName (class t)) ": " (.getMessage t))
                     :error-class (str (.getSimpleName (class t)))})))))]
    (.setDaemon t true)
    (.start t)
    (let [r (deref result *eval-timeout-ms* ::timeout)]
      (if (= r ::timeout)
        (do (.interrupt t)
            ;; Wait briefly for interrupt to take effect, then abandon
            (when (.isAlive t)
              (Thread/sleep 50))
            {:error "TimeoutException" :error-class "TimeoutException"})
        r))))

(defn do-capture
  "Parallel-eval all expressions on JVM, write reference.edn."
  []
  (let [expressions (do-expand)
        total (count expressions)
        done (atom 0) errors (atom 0)
        start (System/currentTimeMillis)
        chunks (partition-all (max 1 (quot total *capture-threads*)) expressions)
        eval-chunk (fn [exprs]
                     (mapv (fn [{:keys [it eval category]}]
                             (let [r (safe-eval eval)
                                   n (swap! done inc)]
                               (when (:error r) (swap! errors inc))
                               (when (zero? (mod n 1000))
                                 (binding [*out* *err*]
                                   (println (format "  %d/%d (%.1fs)" n total
                                                    (/ (- (System/currentTimeMillis) start) 1000.0)))))
                               (merge {:expr eval :category category :it it} r)))
                           exprs))
        pool (java.util.concurrent.Executors/newFixedThreadPool *capture-threads*)
        callables (mapv (fn [chunk] (reify java.util.concurrent.Callable
                                      (call [_] (eval-chunk chunk))))
                        chunks)
        futures (.invokeAll pool callables)
        results (vec (mapcat #(.get %) futures))
        elapsed (/ (- (System/currentTimeMillis) start) 1000.0)]
    (.shutdown pool)
    (io/make-parents reference-file)
    (spit reference-file (pr-str results))
    (println (format "  %d results in %.1fs (%d values, %d errors)"
                     total elapsed (- total @errors) @errors))
    results))

(defn do-verify
  "Sanity-check that reference.edn is well-formed."
  []
  (let [ref (edn/read-string (slurp reference-file))
        n (count ref) ok (count (remove :error ref))]
    (println (format "  %d expressions, %d values, %d errors — OK" n ok (- n ok)))))

;; =============================================================================
;; Emit parity.cljc
;; =============================================================================

(defn- esc [s]
  (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n") (str/replace "\r" "\\r") (str/replace "\t" "\\t")))

(defn- portable? [{:keys [expr it result error]}]
  (and result (not error)
       (not-any? #(str/starts-with? result %) ["#object" "#<" "#error"])
       (not (str/includes? result "..."))
       (not (re-find #"gensym|rand|uuid|shuffle|thread-bind|elapsed|class.?loader|all-ns|loaded-libs|definterface"
                     (str/lower-case (str it))))
       (not (re-find #"java\.|clojure\.lang\." expr))))

(defn- core-cat? [cat]
  (let [s (name cat)]
    (and (str/starts-with? s "clojure.core")
         (not (re-find #"clojure\.core\.(reducers|server|protocols\.clojure)" s)))))

(def ^:private cljc-runner
  ";; parity.cljc — generated by clojure.parity
;; Drop into your implementation and run it.
;; Needs: def, defn, if, do, =, +, str, pr-str, println

(def _p 0) (def _f 0) (def _l nil) (def _lp 0) (def _lt 0)
(defn _r [] (if _l (println (str \"  \" _l \": \" _lp \"/\" _lt))))
(defn _b [n] (_r) (def _l n) (def _lp 0) (def _lt 0))
(defn _t [n r e] (def _lt (+ _lt 1))
  (if (= (pr-str r) e)
    (do (def _p (+ _p 1)) (def _lp (+ _lp 1)))
    (do (def _f (+ _f 1)) (println (str \"  FAIL \" n \": expected \" e \", got \" (pr-str r))))))
(println \"clojure.parity\\n\")
")

(defn emit-cljc
  "Generate a self-contained parity.cljc test file from reference data."
  [output-path]
  (let [ref (edn/read-string (slurp reference-file))
        tests (filter portable? ref)
        by-cat (into (sorted-map) (filter (fn [[k _]] (core-cat? k)) (group-by :category tests)))
        n (atom 0)]
    (io/make-parents output-path)
    (with-open [w (io/writer output-path)]
      (.write w cljc-runner)
      (doseq [[cat entries] by-cat]
        (.write w (str "(_b \"" (name cat) "\")\n"))
        (doseq [{:keys [expr it result]} entries]
          (.write w (str "(_t \"" (esc it) "\" " expr " \"" (esc result) "\")\n"))
          (swap! n inc))
        (.write w "\n"))
      (.write w "(_r)\n(println)\n(println (str _p \"/\" (+ _p _f) \" pass\"))\n(if (= _f 0) (println \"All tests passed.\") (println (str _f \" failures.\")))\n"))
    (println (format "  Generated %s (%d tests)" output-path @n))))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main
  "CLI entry point."
  [& args]
  (let [args (vec args)
        cmd (some #{"init" "capture" "expand" "verify" "cljc"} args)
        scope (cond (some #{"--lang"} args)    :lang
                    (some #{"--contrib"} args) :contrib
                    :else                      :all)
        args (vec (remove #{"--quick" "--balanced" "--thorough" "--lang" "--contrib"
                            "init" "capture" "expand" "verify" "cljc"} args))
        pairs (partition 2 1 args)
        write-dir (some #(when (= "--write" (first %)) (second %)) pairs)
        write-args (when write-dir
                     (let [idx (.indexOf args "--write")]
                       (when (< (+ idx 2) (count args))
                         [(nth args (+ idx 1)) (nth args (+ idx 2))])))
        lang-dir (first write-args)
        contrib-dir (second write-args)
        stats-only (some #{"--stats"} args)
        ns-args (vec (remove #(str/starts-with? % "--") args))
        ns-args (reduce (fn [a d] (if d (vec (remove #{d} a)) a))
                        ns-args [lang-dir contrib-dir])
        scope-ns (case scope
                   :lang default-namespaces
                   :contrib (vec (map str contrib-namespaces))
                   :all all-namespaces)
        namespaces (if (seq ns-args) ns-args scope-ns)]
    (cond
      (= cmd "capture") (do-capture)
      (= cmd "expand")  (do-expand)
      (= cmd "verify")  (do-verify)
      (= cmd "cljc")    (emit-cljc (or (first ns-args) "parity.cljc"))

      ;; All-in-one: reflect → specs → expand → capture → verify → cljc
      (= cmd "init")
      (let [_ (load-namespaces! scope)
            ld (or lang-dir "lang/")
            cd (or contrib-dir "contrib/")
            results (mapv process-namespace namespaces)]
        (print-stats results)
        (write-specs results ld cd)
        (do-capture)
        (do-verify)
        (emit-cljc "parity.cljc"))

      ;; Spec generation only (no capture)
      :else
      (let [_ (load-namespaces! scope)
            _ (binding [*out* *err*]
                (println (format "generate: reflecting on %d namespaces..." (count namespaces))))
            results (mapv process-namespace namespaces)]
        (print-stats results)
        (when (and lang-dir contrib-dir)
          (write-specs results lang-dir contrib-dir))))))

