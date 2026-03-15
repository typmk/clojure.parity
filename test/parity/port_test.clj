(ns parity.port-test
  (:require [clojure.test :refer [deftest is testing]]
            [parity.port :as port]))

;; =============================================================================
;; Naming conventions
;; =============================================================================

(deftest camel->kebab-basic
  (is (= "get-name" (port/camel->kebab "getName")))
  (is (= "to-string" (port/camel->kebab "toString")))
  (is (= "is-empty" (port/camel->kebab "isEmpty")))
  (is (= "hash-code" (port/camel->kebab "hashCode"))))

(deftest rt-replacement-core-ops
  (testing "core ops get p/ prefix"
    (is (= "p/-conj" (port/rt-replacement "conj")))
    (is (= "p/-seq" (port/rt-replacement "seq")))
    (is (= "p/-first" (port/rt-replacement "first")))
    (is (= "p/-count" (port/rt-replacement "count")))))

(deftest rt-replacement-casts
  (testing "cast methods strip -cast suffix"
    (is (= "long" (port/rt-replacement "longCast")))
    (is (= "int" (port/rt-replacement "intCast")))))

(deftest numbers-replacement-basic
  (is (= "h/-add" (port/numbers-replacement "add")))
  (is (= "h/-multiply" (port/numbers-replacement "multiply"))))

(deftest util-replacement-basic
  (is (= "p/-equiv" (port/util-replacement "equiv")))
  (is (= "p/-hash" (port/util-replacement "hash"))))

(deftest static-replacement-dispatch
  (is (some? (port/static-replacement "clojure.lang.RT" "conj")))
  (is (some? (port/static-replacement "clojure.lang.Numbers" "add")))
  (is (some? (port/static-replacement "clojure.lang.Util" "equiv")))
  (is (nil? (port/static-replacement "java.lang.String" "valueOf"))))

;; =============================================================================
;; JVM detection
;; =============================================================================

(deftest jvm-type-detection
  (is (true? (port/jvm-type? "clojure.lang.RT")))
  (is (true? (port/jvm-type? "java.lang.String")))
  (is (true? (port/jvm-type? "java.util.Map")))
  (is (false? (port/jvm-type? "my.custom.Type"))))

(deftest jvm-exception-detection
  (is (port/jvm-exception? "IllegalArgumentException"))
  (is (port/jvm-exception? "NullPointerException"))
  (is (port/jvm-exception? "ClassCastException"))
  (is (nil? (port/jvm-exception? "MyCustomException"))))

;; =============================================================================
;; Host contract loading
;; =============================================================================

(deftest load-host-contract-structure
  (let [host (port/load-host-contract)]
    (testing "has expected top-level keys"
      (is (contains? host :specials))
      (is (contains? host :interfaces))
      (is (contains? host :concretes))
      (is (contains? host :bridge)))
    (testing "specials includes core forms"
      (is (contains? (:specials host) "def"))
      (is (contains? (:specials host) "if"))
      (is (contains? (:specials host) "fn*")))
    (testing "bridge includes RT"
      (is (contains? (:bridge host) "RT")))))

;; =============================================================================
;; Symbol table generation
;; =============================================================================

(deftest symbol-table-has-entries
  (let [host (port/load-host-contract)
        table (port/generate-symbol-table host)]
    (is (pos? (count table)) "symbol table should not be empty")
    (testing "RT statics are mapped"
      (is (some? (get table 'clojure.lang.RT/conj))))
    (testing "interface refs are mapped"
      (is (some? (get table 'clojure.lang.ISeq))))))

(deftest instance-table-has-entries
  (let [host (port/load-host-contract)
        table (port/generate-instance-table host)]
    (is (pos? (count table)))
    (testing "maps to satisfies? form"
      (let [entry (get table 'clojure.lang.ISeq)]
        (is (some? entry))
        (is (= 'satisfies? (first entry)))))))
