(ns parity.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [parity.generate :as gen]
            [parity.core :as core]))

;; =============================================================================
;; End-to-end: init → reference → parity.cljc → self-test
;; =============================================================================

(deftest init-generates-artifacts
  (testing "init --lang produces expected files"
    ;; Run init
    (gen/load-namespaces! :lang)
    (let [results (mapv gen/process-namespace gen/default-namespaces)]
      (gen/write-specs results "lang/" "contrib/")
      (gen/do-capture)
      (gen/do-verify)
      (gen/emit-cljc "parity.cljc"))

    (is (.exists (io/file "results/expressions.edn")) "expressions.edn should exist")
    (is (.exists (io/file "results/reference.edn")) "reference.edn should exist")
    (is (.exists (io/file "parity.cljc")) "parity.cljc should exist")))

(deftest reference-structure
  (testing "reference entries have required keys"
    (let [ref (edn/read-string (slurp "results/reference.edn"))
          sample (first ref)]
      (is (pos? (count ref)) "reference should not be empty")
      (is (contains? sample :expr) "entry should have :expr")
      (is (or (contains? sample :result) (contains? sample :error))
          "entry should have :result or :error"))))

(deftest expressions-structure
  (testing "expressions entries have required keys"
    (let [exprs (edn/read-string (slurp "results/expressions.edn"))
          sample (first exprs)]
      (is (pos? (count exprs)))
      (is (contains? sample :it))
      (is (contains? sample :eval))
      (is (contains? sample :ns)))))

(deftest error-rate-under-threshold
  (testing "error rate should be under 35%"
    (let [ref (edn/read-string (slurp "results/reference.edn"))
          total (count ref)
          errors (count (filter :error ref))
          rate (/ (double errors) total)]
      (is (< rate 0.35) (format "error rate %.1f%% exceeds 35%%" (* 100 rate))))))

(deftest parity-cljc-portable
  (testing "parity.cljc contains no JVM references"
    (let [content (slurp "parity.cljc")
          lines (remove #(str/starts-with? (str/trim %) ";;") (str/split-lines content))]
      (is (not-any? #(re-find #"clojure\.lang\." %) lines)
          "should not contain clojure.lang references")
      (is (not-any? #(re-find #"java\.(lang|util|io)\." %) lines)
          "should not contain java references"))))
