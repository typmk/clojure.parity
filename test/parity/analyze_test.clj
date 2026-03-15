(ns parity.analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [parity.analyze.roots :as roots]
            [parity.analyze.branch :as branch]))

;; =============================================================================
;; roots — JVM host contract reflection
;; =============================================================================

(deftest collect-host-structure
  (let [host (roots/collect-host)]
    (testing "top-level keys"
      (is (contains? host :specials))
      (is (contains? host :interfaces))
      (is (contains? host :abstracts))
      (is (contains? host :concretes))
      (is (contains? host :bridge)))
    (testing "specials are strings"
      (is (every? string? (:specials host))))
    (testing "interfaces have expected fields"
      (let [iface (first (:interfaces host))]
        (is (contains? iface :name))
        (is (contains? iface :methods))
        (is (contains? iface :interface?))
        (is (true? (:interface? iface)))))
    (testing "bridge has RT/Numbers/Util"
      (is (contains? (:bridge host) "RT"))
      (is (contains? (:bridge host) "Numbers"))
      (is (contains? (:bridge host) "Util")))
    (testing "RT bridge has conj"
      (is (contains? (get-in host [:bridge "RT" :ops]) "conj")))))

(deftest collect-host-counts
  (let [host (roots/collect-host)]
    (is (> (count (:specials host)) 10) "should have >10 specials")
    (is (> (count (:interfaces host)) 20) "should have >20 interfaces")
    (is (> (count (:concretes host)) 20) "should have >20 concretes")))

;; =============================================================================
;; branch — source dependency analysis
;; =============================================================================

(deftest host-ref-detection
  (testing "clojure.lang references detected"
    (is (true? (branch/host-ref? "clojure.lang.RT/conj")))
    (is (true? (branch/host-ref? "clojure.lang.Numbers/add")))
    (is (true? (branch/host-ref? "clojure.lang.PersistentVector"))))
  (testing "java references detected"
    (is (true? (branch/host-ref? "java.lang.String")))
    (is (true? (branch/host-ref? "java.util.Map"))))
  (testing "interop method calls detected"
    (is (true? (branch/host-ref? ".getName")))
    (is (true? (branch/host-ref? ".hashCode"))))
  (testing "normal symbols not detected"
    (is (false? (branch/host-ref? "map")))
    (is (false? (branch/host-ref? "reduce")))
    (is (false? (branch/host-ref? "my-function")))))

(deftest parse-host-ref-structure
  (let [ref (branch/parse-host-ref "clojure.lang.RT/conj")]
    (is (= "clojure.lang" (:pkg ref)))
    (is (= "RT" (:class ref)))
    (is (= "conj" (:member ref))))
  (let [ref (branch/parse-host-ref ".getName")]
    (is (= :interop (:pkg ref)))
    (is (= "getName" (:member ref)))))

(deftest collect-symbols-basic
  (testing "collects symbols from forms"
    (let [syms (branch/collect-symbols '(defn foo [x] (+ x (bar x))))]
      (is (contains? syms "defn"))
      (is (contains? syms "foo"))
      (is (contains? syms "+"))
      (is (contains? syms "bar"))
      (is (contains? syms "x"))))
  (testing "collects from nested structures"
    (let [syms (branch/collect-symbols '(let [m {:a 1}] (get m :a)))]
      (is (contains? syms "let"))
      (is (contains? syms "get"))
      (is (contains? syms "m")))))
