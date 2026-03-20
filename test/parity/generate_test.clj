(ns parity.generate-test
  (:require [clojure.test :refer [deftest is testing]]
            [parity.generate :as gen]))

;; =============================================================================
;; arg-type — name-based inference
;; =============================================================================

(deftest arg-type-numerics
  (testing "numeric arg names"
    (doseq [name ["n" "x" "y" "start" "end" "index" "i"]]
      (is (= :num (gen/arg-type name "clojure.core"))
          (str name " should be :num")))))

(deftest arg-type-strings
  (testing "string arg names"
    (doseq [name ["s" "string" "msg" "prefix"]]
      (is (= :string (gen/arg-type name "clojure.core"))
          (str name " should be :string"))))
  (testing "clojure.string namespace defaults to :string"
    (is (= :string (gen/arg-type "unknown-arg" "clojure.string")))))

(deftest arg-type-collections
  (testing "collection arg names"
    (doseq [name ["coll" "c" "xs"]]
      (is (= :coll (gen/arg-type name "clojure.core"))
          (str name " should be :coll"))))
  (testing "names ending in s → :coll"
    (is (= :coll (gen/arg-type "items" "clojure.core")))))

(deftest arg-type-functions
  (doseq [name ["f" "fn" "pred" "g"]]
    (is (= :fn (gen/arg-type name "clojure.core"))
        (str name " should be :fn"))))

(deftest arg-type-fallback
  (is (= :any (gen/arg-type "something-unknown" "clojure.core"))))

;; =============================================================================
;; tag-type — JVM metadata
;; =============================================================================

(deftest tag-type-known
  (is (= :string (gen/tag-type (with-meta 's {:tag 'String}))))
  (is (= :num (gen/tag-type (with-meta 'n {:tag 'long}))))
  (is (= :num (gen/tag-type (with-meta 'n {:tag 'double}))))
  (is (= :fn (gen/tag-type (with-meta 'f {:tag 'clojure.lang.IFn}))))
  (is (= :map (gen/tag-type (with-meta 'm {:tag 'clojure.lang.IPersistentMap})))))

(deftest tag-type-skip
  (testing "untestable JVM types return :skip"
    (is (= :skip (gen/tag-type (with-meta 'a {:tag 'clojure.lang.Agent}))))
    (is (= :skip (gen/tag-type (with-meta 'r {:tag 'clojure.lang.Ref}))))
    (is (= :skip (gen/tag-type (with-meta 'v {:tag 'clojure.lang.Var})))))
  (testing "unknown clojure.lang types return :skip"
    (is (= :skip (gen/tag-type (with-meta 'x {:tag 'clojure.lang.SomeFutureThing}))))))

(deftest tag-type-nil-when-no-tag
  (is (nil? (gen/tag-type 'x)))
  (is (nil? (gen/tag-type (with-meta 'x {})))))

;; =============================================================================
;; effective-type — tag overrides name
;; =============================================================================

(deftest effective-type-tag-wins
  (testing "tag metadata overrides name-based inference"
    (let [arg (with-meta 'n {:tag 'String})]
      (is (= :string (gen/effective-type arg "clojure.core"))
          "tag String should override numeric name 'n'"))))

(deftest effective-type-falls-back-to-name
  (testing "no tag → name-based"
    (is (= :num (gen/effective-type 'x "clojure.core")))))

;; =============================================================================
;; classify-var
;; =============================================================================

(deftest classify-var-skips-dynamic
  (is (= :skip (gen/classify-var '*ns* {:arglists nil}))))

(deftest classify-var-skips-no-arglists
  (is (= :skip (gen/classify-var 'foo {:arglists nil}))))

(deftest classify-var-testable
  (is (= :testable (gen/classify-var 'assoc {:arglists '([map key val])}))))

(deftest classify-var-skips-constructors
  (is (= :skip (gen/classify-var '->Foo {:arglists '([x])})))
  (is (= :skip (gen/classify-var 'map->Foo {:arglists '([m])}))))

;; =============================================================================
;; gen-tests — test shape
;; =============================================================================

(deftest gen-tests-nullary
  (let [tests (gen/gen-tests 'foo {:arglists '([])} "clojure.core")]
    (is (= 1 (count tests)))
    (is (= "(foo)" (:eval (first tests))))))

(deftest gen-tests-predicate
  (let [tests (gen/gen-tests 'even? {:arglists '([n])} "clojure.core")]
    (is (>= (count tests) 18) "predicates test at least 18 types (more with spec)")
    (is (every? #(re-find #"even\?" (:eval %)) tests))))

(deftest gen-tests-skips-untestable-tags
  (let [tests (gen/gen-tests 'foo
                {:arglists (list [(with-meta 'a {:tag 'clojure.lang.Agent})])}
                "clojure.core")]
    (is (nil? tests) "should skip when arg has untestable tag")))

;; =============================================================================
;; gen-tests — multi-arity
;; =============================================================================

(deftest gen-tests-multi-arity
  (testing "functions with multiple arities generate tests for each"
    (let [tests (gen/gen-tests 'get {:arglists '([map key] [map key not-found])} "clojure.core")
          evals (set (map :eval tests))]
      (is (some #(re-find #"^\(get \{" %) evals) "should have 2-arg test")
      (is (some #(re-find #"^\(get \{.*\}.*:.*42" %) evals) "should have 3-arg test")
      (is (> (count tests) 5) "should generate multiple variants per arity"))))

(deftest gen-tests-varies-all-args
  (testing "nil/empty variants generated for each arg position, not just first"
    (let [tests (gen/gen-tests 'assoc {:arglists '([map key val])} "clojure.core")
          evals (map :eval tests)]
      (is (some #(re-find #"^\(assoc nil " %) evals) "should test nil in first position")
      (is (some #(re-find #"^\(assoc \{\} " %) evals) "should test empty in first position"))))

(deftest gen-tests-alt-values
  (testing ":alts values generate extra tests per arg position"
    (let [tests (gen/gen-tests 'assoc {:arglists '([map key val])} "clojure.core")
          evals (set (map :eval tests))]
      (is (> (count tests) 3) "should have more than just happy/nil/empty")
      ;; Alt maps
      (is (some #(re-find #"\{:a 1 :b 2\}" %) evals) "should test alt map value")
      ;; Alt keys
      (is (some #(re-find #":b " %) evals) "should test alt key value")
      ;; Alt numbers
      (is (some #(re-find #" -1\)" %) evals) "should test alt number value"))))

(deftest gen-tests-nullary-plus-arity
  (testing "functions with nullary + other arities get both"
    (let [tests (gen/gen-tests 'concat {:arglists '([] [x] [x y] [x y & zs])} "clojure.core")
          evals (set (map :eval tests))]
      (is (contains? evals "(concat)") "should have nullary test")
      (is (some #(re-find #"^\(concat \d" %) evals) "should have 1-arg test"))))

;; =============================================================================
;; safe-eval
;; =============================================================================

(deftest safe-eval-success
  (let [r (gen/safe-eval "(+ 1 2)")]
    (is (:result r))
    (is (= "3" (:result r)))))

(deftest safe-eval-error
  (let [r (gen/safe-eval "(/ 1 0)")]
    (is (:error r))
    (is (string? (:error-class r)))))

(deftest safe-eval-timeout
  (binding [gen/*eval-timeout-ms* 100]
    (let [r (gen/safe-eval "(loop [] (recur))")]
      (is (= "TimeoutException" (:error-class r))))))

;; =============================================================================
;; process-namespace
;; =============================================================================

(deftest process-namespace-core
  (let [result (gen/process-namespace "clojure.core")]
    (is (pos? (:total result)))
    (is (pos? (:generated result)))
    (is (> (:generated result) 3000) "should generate 3000+ tests with multi-arity + alts")
    (is (zero? (count (filter :error [result]))))))
