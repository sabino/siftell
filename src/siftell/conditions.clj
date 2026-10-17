(ns siftell.conditions
  "Compile declarative conditions to reusable functions of one unmodified input.
  Paths name exact map keys and explicit sequential indexes; no record is selected implicitly."
  (:require [clojure.string :as str]))

(def ^:private absent (Object.))
(def ^:private value-ops #{:eq :neq :in :not-in :gt :gte :lt :lte :between :contains})
(def ^:private presence-ops #{:present :missing :nil?})
(def ^:private numeric-ops #{:gt :gte :lt :lte :between})

(defn- reject! [kind message path & [op]]
  (throw (ex-info message (cond-> {:siftell/error kind :path path} op (assoc :op op)))))

(defn- finite-number? [x]
  (and (number? x)
       (cond (double? x) (Double/isFinite x)
             (float? x) (Float/isFinite x)
             :else true)))

(defn- literal? [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (char? x) (keyword? x) (symbol? x) (uuid? x) (inst? x)) true
    (number? x) (finite-number? x)
    (map? x) (every? (fn [[k v]] (and (literal? k) (literal? v))) x)
    (or (sequential? x) (set? x)) (every? literal? x)
    :else false))

(defn- path-reader [path]
  (when-not (and (vector? path)
                (every? #(or (keyword? %) (string? %) (and (integer? %) (<= 0 %))) path))
    (reject! :invalid-condition "A condition path must contain exact keys or nonnegative indexes." []))
  (fn [input]
    (loop [value input at 0]
      (if (= at (count path))
        value
        (let [step (nth path at)
              next-value (cond
                           (map? value) (if (contains? value step) (get value step) absent)
                           (and (sequential? value) (integer? step) (<= step Integer/MAX_VALUE)) (nth value (int step) absent)
                           :else absent)]
          (if (identical? absent next-value) absent (recur next-value (inc at))))))))

(defn- allowed-keys! [spec expected]
  (when-not (every? expected (keys spec))
    (reject! :invalid-condition "A condition contains unsupported fields." [])))

(defn- numeric-predicate [op operand path]
  (let [compare-value (case op :gt > :gte >= :lt < :lte <=
                           :between (let [[lower upper] operand] #(<= lower % upper)))]
    (fn [value]
      (when-not (finite-number? value)
        (reject! :invalid-value "Ordering requires a finite numeric value." path op))
      (try (boolean (if (= :between op) (compare-value value) (compare-value value operand)))
           (catch Exception _
             (reject! :invalid-value "The numeric values cannot be compared." path op))))))

(defn- value-predicate [{:keys [op value path]}]
  (when-not (literal? value)
    (reject! :invalid-condition "A comparison operand must be declarative finite data." path op))
  (cond
    (numeric-ops op)
    (do
      (when-not (if (= :between op)
                  (and (vector? value) (= 2 (count value)) (every? finite-number? value)
                       (try (<= (first value) (second value)) (catch Exception _ false)))
                  (finite-number? value))
        (reject! :invalid-condition "Ordering requires a numeric operand or an ascending two-value interval." path op))
      (numeric-predicate op value path))

    (#{:in :not-in} op)
    (do
      (when-not (or (sequential? value) (set? value))
        (reject! :invalid-condition "Membership requires a collection of literal operands." path op))
      (let [members (set value)]
        (if (= :in op) #(contains? members %) #(not (contains? members %)))))

    (= :contains op)
    (fn [actual]
      (cond
        (string? actual) (if (string? value) (str/includes? actual value)
                            (reject! :invalid-value "A substring condition requires a string operand." path op))
        (or (sequential? actual) (set? actual)) (boolean (some #(= value %) actual))
        :else (reject! :invalid-value "Contains requires a string or a sequential or set collection." path op)))

    (= :eq op) #(= % value)
    (= :neq op) #(not= % value)))

(declare compile-condition)

(defn- compile-group [spec key]
  (allowed-keys! spec #{key})
  (let [children (get spec key)]
    (when-not (vector? children)
      (reject! :invalid-condition "Logical groups require a vector of conditions." []))
    (let [predicates (mapv compile-condition children)]
      (if (= key :all)
        (fn [input] (every? #(% input) predicates))
        (fn [input] (boolean (some #(% input) predicates)))))))

(defn compile-condition
  "Compile a condition once. Missing paths differ from present nil values.
  Groups short-circuit in declaration order. Quantifiers evaluate their :where
  condition relative to each sequential item; some/every of an empty sequence
  are false/true. A missing quantified path is false; a non-sequential value fails."
  [spec]
  (when-not (map? spec)
    (reject! :invalid-condition "A condition must be a map." []))
  (cond
    (contains? spec :all) (compile-group spec :all)
    (contains? spec :any) (compile-group spec :any)
    (contains? spec :not)
    (do (allowed-keys! spec #{:not})
        (let [predicate (compile-condition (:not spec))] (fn [input] (not (predicate input)))))
    :else
    (let [{:keys [path op]} spec]
      (when-not (and (contains? spec :path) (contains? spec :op)
                     (or (value-ops op) (presence-ops op) (#{:some :every} op)))
        (reject! :invalid-condition "Choose a supported condition operator and an explicit path." []))
      (let [read-value (path-reader path)]
        (cond
          (#{:some :every} op)
          (do
            (allowed-keys! spec #{:path :op :where})
            (when-not (contains? spec :where)
              (reject! :invalid-condition "A quantified condition requires :where." path op))
            (let [predicate (compile-condition (:where spec))]
              (fn [input]
                (let [items (read-value input)]
                  (cond
                    (identical? absent items) false
                    (not (sequential? items))
                    (reject! :invalid-value "A quantified condition requires a sequential collection." path op)
                    (= :some op) (boolean (some predicate items))
                    :else (every? predicate items))))))

          (presence-ops op)
          (do (allowed-keys! spec #{:path :op})
              (fn [input]
                (let [value (read-value input) missing? (identical? absent value)]
                  (case op :present (not missing?) :missing missing? :nil? (and (not missing?) (nil? value))))))

          :else
          (do
            (allowed-keys! spec #{:path :op :value})
            (when-not (contains? spec :value)
              (reject! :invalid-condition "The condition requires an explicit comparison operand." path op))
            (let [predicate (value-predicate spec)]
              (fn [input]
                (let [value (read-value input)]
                  (if (identical? absent value) false (boolean (predicate value))))))))))))
