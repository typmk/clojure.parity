(ns parity.scaffold-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [parity.scaffold :as scaffold]))

(deftest generate-scaffold-structure
  (let [output (scaffold/generate-scaffold)]
    (testing "contains protocols section"
      (is (str/includes? output "defprotocol")))
    (testing "contains key interfaces"
      (is (str/includes? output "(defprotocol ISeq"))
      (is (str/includes? output "(defprotocol Associative"))
      (is (str/includes? output "(defprotocol IFn")))
    (testing "contains bridge section"
      (is (str/includes? output "Bridge functions"))
      (is (str/includes? output "defn rt-"))
      (is (str/includes? output "defn num-")))
    (testing "contains concrete types section"
      (is (str/includes? output "Concrete types"))
      (is (str/includes? output "PersistentVector"))
      (is (str/includes? output "PersistentHashMap")))
    (testing "no Java noise in protocols"
      (is (not (str/includes? output "getAsBoolean")))
      (is (not (str/includes? output "getAsDouble"))))
    (testing "bridge stubs use valid names (no slashes)"
      (is (not (re-find #"\(defn \S+/" output))))))

(deftest method-params-generation
  (is (= [] (scaffold/method-params 0)))
  (is (= ['a] (scaffold/method-params 1)))
  (is (= ['a 'b] (scaffold/method-params 2)))
  (is (= 5 (count (scaffold/method-params 5)))))

(deftest emit-protocol-basic
  (let [iface {:name "IFoo"
               :instance [{:name "bar" :arity 1 :static? false :return "Object"}
                           {:name "baz" :arity 0 :static? false :return "void"}]
               :interfaces ["IBar"]}
        output (scaffold/emit-protocol iface)]
    (is (str/includes? output "(defprotocol IFoo"))
    (is (str/includes? output "(bar [this a])"))
    (is (str/includes? output "(baz [this])"))
    (is (str/includes? output ";; extends: IBar"))))
