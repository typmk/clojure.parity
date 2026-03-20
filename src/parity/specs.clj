(ns parity.specs
  "Specs for clojure.core functions — used by parity for test generation.
   When a function has an fdef here, parity uses spec's generators instead
   of name-based heuristics. Produces more diverse, correct inputs."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; =============================================================================
;; Helpers — constrained generators that produce serializable values
;; =============================================================================

(def ^:private simple-value
  "Generator-friendly spec: values that can be pr-str'd and read back."
  (s/or :nil nil? :bool boolean? :int int? :double (s/double-in :infinite? false :NaN? false)
        :string string? :keyword keyword? :symbol symbol?
        :vec (s/coll-of int? :kind vector? :max-count 4)
        :map (s/map-of keyword? int? :max-count 3)
        :set (s/coll-of int? :kind set? :max-count 4)))

(s/def ::simple-value simple-value)

;; =============================================================================
;; Core function specs
;; =============================================================================

;; --- Arithmetic ---
(s/fdef clojure.core/+ :args (s/* number?) :ret number?)
(s/fdef clojure.core/- :args (s/cat :x number? :more (s/* number?)) :ret number?)
(s/fdef clojure.core/* :args (s/* number?) :ret number?)
(s/fdef clojure.core/inc :args (s/cat :x number?) :ret number?)
(s/fdef clojure.core/dec :args (s/cat :x number?) :ret number?)
(s/fdef clojure.core/max :args (s/cat :x number? :more (s/* number?)) :ret number?)
(s/fdef clojure.core/min :args (s/cat :x number? :more (s/* number?)) :ret number?)
(s/fdef clojure.core/abs :args (s/cat :x number?) :ret number?)
(s/fdef clojure.core/mod :args (s/cat :num int? :div (s/and int? pos?)) :ret int?)
(s/fdef clojure.core/rem :args (s/cat :num int? :div (s/and int? #(not (zero? %)))) :ret int?)
(s/fdef clojure.core/quot :args (s/cat :num int? :div (s/and int? #(not (zero? %)))) :ret int?)

;; --- Comparison ---
(s/fdef clojure.core/= :args (s/cat :x any? :more (s/* any?)) :ret boolean?)
(s/fdef clojure.core/not= :args (s/cat :x any? :y any?) :ret boolean?)
(s/fdef clojure.core/< :args (s/cat :x number? :more (s/* number?)) :ret boolean?)
(s/fdef clojure.core/<= :args (s/cat :x number? :more (s/* number?)) :ret boolean?)
(s/fdef clojure.core/> :args (s/cat :x number? :more (s/* number?)) :ret boolean?)
(s/fdef clojure.core/>= :args (s/cat :x number? :more (s/* number?)) :ret boolean?)
(s/fdef clojure.core/compare :args (s/cat :x int? :y int?) :ret int?)

;; --- Collections ---
(s/fdef clojure.core/count :args (s/cat :coll (s/nilable (s/or :v vector? :m map? :s set? :str string? :l list?))) :ret nat-int?)
(s/fdef clojure.core/empty? :args (s/cat :coll (s/nilable seqable?)) :ret boolean?)
(s/fdef clojure.core/not-empty :args (s/cat :coll (s/nilable seqable?)) :ret (s/nilable seqable?))
(s/fdef clojure.core/first :args (s/cat :coll (s/nilable seqable?)) :ret any?)
(s/fdef clojure.core/rest :args (s/cat :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/next :args (s/cat :coll (s/nilable seqable?)) :ret (s/nilable seqable?))
(s/fdef clojure.core/last :args (s/cat :coll (s/nilable seqable?)) :ret any?)
(s/fdef clojure.core/butlast :args (s/cat :coll (s/nilable seqable?)) :ret (s/nilable seqable?))
(s/fdef clojure.core/second :args (s/cat :coll (s/nilable seqable?)) :ret any?)
(s/fdef clojure.core/nth :args (s/cat :coll (s/or :v vector? :l list? :str string?) :n nat-int?) :ret any?)
(s/fdef clojure.core/peek :args (s/cat :coll (s/or :v (s/and vector? not-empty) :l (s/and list? not-empty))) :ret any?)
(s/fdef clojure.core/pop :args (s/cat :coll (s/or :v (s/and vector? not-empty) :l (s/and list? not-empty))) :ret (s/or :v vector? :l list?))
(s/fdef clojure.core/conj :args (s/cat :coll (s/nilable coll?) :x any? :more (s/* any?)) :ret coll?)
(s/fdef clojure.core/cons :args (s/cat :x any? :coll (s/nilable seqable?)) :ret seq?)
(s/fdef clojure.core/into :args (s/cat :to (s/or :v vector? :m map? :s set? :l list?) :from (s/nilable seqable?)) :ret coll?)
(s/fdef clojure.core/concat :args (s/* (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/reverse :args (s/cat :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/flatten :args (s/cat :x any?) :ret seqable?)
(s/fdef clojure.core/distinct :args (s/cat :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/sort :args (s/cat :coll (s/coll-of int? :max-count 8)) :ret seqable?)
(s/fdef clojure.core/take :args (s/cat :n nat-int? :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/drop :args (s/cat :n nat-int? :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/take-while :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/drop-while :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/partition :args (s/cat :n pos-int? :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/interleave :args (s/cat :c1 (s/nilable seqable?) :c2 (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/interpose :args (s/cat :sep any? :coll (s/nilable seqable?)) :ret seqable?)
(s/fdef clojure.core/frequencies :args (s/cat :coll (s/nilable seqable?)) :ret map?)
(s/fdef clojure.core/group-by :args (s/cat :f ifn? :coll (s/nilable seqable?)) :ret map?)
(s/fdef clojure.core/zipmap :args (s/cat :keys (s/nilable seqable?) :vals (s/nilable seqable?)) :ret map?)
(s/fdef clojure.core/repeat :args (s/cat :n (s/int-in 0 10) :x any?) :ret seqable?)
(s/fdef clojure.core/range :args (s/alt :nullary (s/cat) :unary (s/cat :end (s/int-in 0 20)) :binary (s/cat :start int? :end (s/int-in 0 20))) :ret seqable?)

;; --- Maps ---
(s/fdef clojure.core/assoc :args (s/cat :map (s/nilable associative?) :key any? :val any? :more (s/* (s/cat :k any? :v any?))) :ret associative?)
(s/fdef clojure.core/dissoc :args (s/cat :map (s/nilable map?) :keys (s/* any?)) :ret (s/nilable map?))
(s/fdef clojure.core/get :args (s/alt :binary (s/cat :map any? :key any?) :ternary (s/cat :map any? :key any? :not-found any?)) :ret any?)
(s/fdef clojure.core/get-in :args (s/alt :binary (s/cat :m any? :ks (s/coll-of any? :kind vector?)) :ternary (s/cat :m any? :ks (s/coll-of any? :kind vector?) :not-found any?)) :ret any?)
(s/fdef clojure.core/assoc-in :args (s/cat :m (s/nilable associative?) :ks (s/coll-of any? :kind vector? :min-count 1) :v any?) :ret associative?)
(s/fdef clojure.core/update :args (s/cat :m (s/nilable associative?) :k any? :f ifn? :more (s/* any?)) :ret associative?)
(s/fdef clojure.core/update-in :args (s/cat :m (s/nilable associative?) :ks (s/coll-of any? :kind vector? :min-count 1) :f ifn? :more (s/* any?)) :ret associative?)
(s/fdef clojure.core/merge :args (s/* (s/nilable map?)) :ret (s/nilable map?))
(s/fdef clojure.core/merge-with :args (s/cat :f ifn? :maps (s/* (s/nilable map?))) :ret (s/nilable map?))
(s/fdef clojure.core/select-keys :args (s/cat :map (s/nilable associative?) :keys (s/nilable seqable?)) :ret map?)
(s/fdef clojure.core/keys :args (s/cat :map (s/nilable map?)) :ret (s/nilable seqable?))
(s/fdef clojure.core/vals :args (s/cat :map (s/nilable map?)) :ret (s/nilable seqable?))
(s/fdef clojure.core/contains? :args (s/cat :coll (s/or :m map? :s set? :v vector?) :key any?) :ret boolean?)
(s/fdef clojure.core/find :args (s/cat :map (s/nilable associative?) :key any?) :ret (s/nilable map-entry?))

;; --- Higher-order ---
(s/fdef clojure.core/map :args (s/alt :xf (s/cat :f ifn?) :unary (s/cat :f ifn? :coll (s/nilable seqable?)) :binary (s/cat :f ifn? :c1 (s/nilable seqable?) :c2 (s/nilable seqable?))) :ret (s/or :xf fn? :seq seqable?))
(s/fdef clojure.core/mapv :args (s/cat :f ifn? :coll (s/nilable seqable?)) :ret vector?)
(s/fdef clojure.core/filter :args (s/alt :xf (s/cat :pred ifn?) :coll (s/cat :pred ifn? :coll (s/nilable seqable?))) :ret (s/or :xf fn? :seq seqable?))
(s/fdef clojure.core/filterv :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret vector?)
(s/fdef clojure.core/remove :args (s/alt :xf (s/cat :pred ifn?) :coll (s/cat :pred ifn? :coll (s/nilable seqable?))) :ret (s/or :xf fn? :seq seqable?))
(s/fdef clojure.core/reduce :args (s/alt :no-init (s/cat :f ifn? :coll (s/nilable seqable?)) :with-init (s/cat :f ifn? :init any? :coll (s/nilable seqable?))) :ret any?)
(s/fdef clojure.core/map-indexed :args (s/alt :xf (s/cat :f ifn?) :coll (s/cat :f ifn? :coll (s/nilable seqable?))) :ret (s/or :xf fn? :seq seqable?))
(s/fdef clojure.core/keep :args (s/alt :xf (s/cat :f ifn?) :coll (s/cat :f ifn? :coll (s/nilable seqable?))) :ret (s/or :xf fn? :seq seqable?))
(s/fdef clojure.core/some :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret any?)
(s/fdef clojure.core/every? :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret boolean?)
(s/fdef clojure.core/not-every? :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret boolean?)
(s/fdef clojure.core/not-any? :args (s/cat :pred ifn? :coll (s/nilable seqable?)) :ret boolean?)
(s/fdef clojure.core/apply :args (s/cat :f ifn? :args (s/* any?) :coll (s/nilable seqable?)) :ret any?)
(s/fdef clojure.core/partial :args (s/cat :f ifn? :args (s/* any?)) :ret ifn?)
(s/fdef clojure.core/comp :args (s/* ifn?) :ret ifn?)
(s/fdef clojure.core/juxt :args (s/+ ifn?) :ret ifn?)
(s/fdef clojure.core/complement :args (s/cat :f ifn?) :ret ifn?)
(s/fdef clojure.core/identity :args (s/cat :x any?) :ret any?)
(s/fdef clojure.core/constantly :args (s/cat :x any?) :ret ifn?)

;; --- Strings ---
(s/fdef clojure.core/str :args (s/* any?) :ret string?)
(s/fdef clojure.core/subs :args (s/alt :binary (s/cat :s string? :start nat-int?) :ternary (s/cat :s string? :start nat-int? :end nat-int?)) :ret string?)
(s/fdef clojure.core/name :args (s/cat :x (s/or :s string? :k keyword? :sym symbol?)) :ret string?)
(s/fdef clojure.core/namespace :args (s/cat :x (s/or :k keyword? :sym symbol?)) :ret (s/nilable string?))
(s/fdef clojure.core/keyword :args (s/alt :unary (s/cat :x (s/or :s string? :k keyword? :sym symbol?)) :binary (s/cat :ns string? :name string?)) :ret keyword?)
(s/fdef clojure.core/symbol :args (s/alt :unary (s/cat :x (s/or :s string? :sym symbol?)) :binary (s/cat :ns string? :name string?)) :ret symbol?)

;; --- Predicates ---
(s/fdef clojure.core/nil? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/some? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/true? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/false? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/number? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/integer? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/int? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/pos-int? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/neg-int? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/nat-int? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/float? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/double? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/string? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/keyword? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/symbol? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/map? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/vector? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/set? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/list? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/seq? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/coll? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/sequential? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/associative? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/counted? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/seqable? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/fn? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/ifn? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/zero? :args (s/cat :x number?) :ret boolean?)
(s/fdef clojure.core/pos? :args (s/cat :x number?) :ret boolean?)
(s/fdef clojure.core/neg? :args (s/cat :x number?) :ret boolean?)
(s/fdef clojure.core/even? :args (s/cat :x integer?) :ret boolean?)
(s/fdef clojure.core/odd? :args (s/cat :x integer?) :ret boolean?)
(s/fdef clojure.core/empty? :args (s/cat :coll (s/nilable seqable?)) :ret boolean?)

;; --- Misc ---
(s/fdef clojure.core/not :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/hash :args (s/cat :x any?) :ret int?)
(s/fdef clojure.core/pr-str :args (s/* any?) :ret string?)
(s/fdef clojure.core/type :args (s/cat :x any?) :ret any?)
(s/fdef clojure.core/class :args (s/cat :x any?) :ret (s/nilable any?))
(s/fdef clojure.core/meta :args (s/cat :obj any?) :ret (s/nilable map?))
;; with-meta and vary-meta require IObj — hard to generate, skipped

;; =============================================================================
;; Public API
;; =============================================================================

(def ^:private spec-sample-size
  "Number of samples to generate per spec'd function."
  10)

(defn spec-available?
  "True if the given symbol has an fdef registered."
  [sym]
  (boolean (s/get-spec sym)))

(defn gen-from-spec
  "Generate test inputs from a function's fdef spec.
   Returns seq of arg vectors, or nil if no spec or generation fails."
  [sym & {:keys [n] :or {n spec-sample-size}}]
  (when-let [spec (s/get-spec sym)]
    (when-let [args-spec (:args spec)]
      (try
        (gen/sample (s/gen args-spec) n)
        (catch Exception e
          (binding [*out* *err*]
            (println (str "WARNING: spec gen failed for " sym ": " (.getMessage e))))
          nil)))))

(defn args->expr-str
  "Convert a conformed args sample to an expression string.
   Handles s/alt tagged values by extracting the value."
  [sym args]
  (let [qualified (str sym)
        flatten-arg (fn flatten-arg [a]
                      (cond
                        ;; s/alt or s/or produces [tag value]
                        (and (vector? a) (= 2 (count a)) (keyword? (first a)))
                        (pr-str (second a))
                        ;; s/cat produces a map — shouldn't appear at arg level
                        :else (pr-str a)))
        arg-strs (if (sequential? args)
                   (map flatten-arg args)
                   [(flatten-arg args)])]
    (str "(" qualified " " (clojure.string/join " " arg-strs) ")")))
