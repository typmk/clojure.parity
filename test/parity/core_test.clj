(ns parity.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [parity.core :as core]))

;; =============================================================================
;; compare-results
;; =============================================================================

(deftest compare-results-all-pass
  (let [ref [{:expr "(+ 1 2)" :result "3"}
             {:expr "(str :a)" :result "\":a\""}]
        tgt [{:expr "(+ 1 2)" :result "3"}
             {:expr "(str :a)" :result "\":a\""}]
        exprs [{:it "+ 1 2" :eval "(+ 1 2)" :category :core :ns "clojure.core"}
               {:it "str :a" :eval "(str :a)" :category :core :ns "clojure.core"}]
        result (with-out-str (core/compare-results ref tgt exprs))]
    ;; Output should contain pass count
    (is (re-find #"Pass:\s+2/2" result))))

(deftest compare-results-with-failure
  (let [ref [{:expr "(+ 1 2)" :result "3"}]
        tgt [{:expr "(+ 1 2)" :result "4"}]
        exprs [{:it "+ 1 2" :eval "(+ 1 2)" :category :core :ns "clojure.core"}]
        result (with-out-str (core/compare-results ref tgt exprs))]
    (is (re-find #"Fail:\s+1" result))))

(deftest compare-results-missing
  (let [ref [{:expr "(+ 1 2)" :result "3"}]
        tgt []
        exprs [{:it "+ 1 2" :eval "(+ 1 2)" :category :core :ns "clojure.core"}]
        result (with-out-str (core/compare-results ref tgt exprs))]
    (is (re-find #"Missing:\s+1" result))))

(deftest compare-results-error-match
  (let [ref [{:expr "(/ 0)" :error "ArithmeticException: /" :error-class "ArithmeticException"}]
        tgt [{:expr "(/ 0)" :error "ArithmeticException: /" :error-class "ArithmeticException"}]
        exprs [{:it "/ 0" :eval "(/ 0)" :category :core :ns "clojure.core"}]
        result (with-out-str (core/compare-results ref tgt exprs))]
    (is (re-find #"Pass:\s+1/1" result))))
