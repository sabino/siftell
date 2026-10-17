(ns siftell.conditions-test
  (:require [clojure.test :refer [deftest is testing]]
            [siftell.conditions :as c]))

(defn- outcome [f]
  (try (f) (catch clojure.lang.ExceptionInfo e (ex-data e))))
(defn- compile-error [spec]
  (:siftell/error (outcome #(c/compile-condition spec))))

(deftest exact-keys-indexes-and-absence
  (doseq [[op absent present-nil present-false]
          [[:present false true true] [:missing true false false] [:nil? false true false]]]
    (let [p (c/compile-condition {:path [:value] :op op})]
      (is (= absent (p {})))
      (is (= present-nil (p {:value nil})))
      (is (= present-false (p {:value false})))))
  (is ((c/compile-condition {:path [] :op :nil?}) nil))
  (is ((c/compile-condition {:path [:items 1 "name"] :op :eq :value "second"})
       {:items [{"name" "first"} {"name" "second"}]}))
  (is (false? ((c/compile-condition {:path [:items :name] :op :eq :value "first"})
               {:items [{:name "first"}]})))
  (is (false? ((c/compile-condition {:path ["value"] :op :present}) {:value 1})))
  (is ((c/compile-condition {:path [:items 1] :op :eq :value 2}) {:items '(1 2)}))
  (is (false? ((c/compile-condition {:path [:items 999999999999999999999N] :op :present}) {:items [1]})))
  (is ((c/compile-condition {:path [999999999999999999999N] :op :present}) {999999999999999999999N nil})))

(deftest literal-equality-membership-and-nil
  (doseq [op [:eq :neq :in :not-in :contains :gt :gte :lt :lte :between]]
    (let [operand (case op (:in :not-in) [1] :between [0 2] 1)]
      (is (false? ((c/compile-condition {:path [:missing] :op op :value operand}) {})))))
  (is ((c/compile-condition {:path [:x] :op :eq :value nil}) {:x nil}))
  (is ((c/compile-condition {:path [:x] :op :eq :value \a}) {:x \a}))
  (is ((c/compile-condition {:path [:x] :op :neq :value 1}) {:x nil}))
  (is ((c/compile-condition {:path [:x] :op :in :value [nil false 1]}) {:x false}))
  (is (false? ((c/compile-condition {:path [:x] :op :eq :value 1}) {:x 1.0})))
  (is (false? ((c/compile-condition {:path [:x] :op :in :value [1]}) {:x 1M})))
  (is ((c/compile-condition {:path [:x] :op :not-in :value []}) {:x nil})))

(deftest numeric-ordering-and-compile-errors
  (doseq [[op literal yes no]
          [[:gt 2M 3 2] [:gte 2 2M 1] [:lt 2 1 2] [:lte 2 2 3]
           [:between [1 3] 3 4] [:between [1 3] 1 0]]]
    (let [p (c/compile-condition {:path [:n] :op op :value literal})]
      (is (true? (p {:n yes})))
      (is (false? (p {:n no})))
      (doseq [bad [nil false "SECRET-CUSTOMER-DATA" Double/NaN Double/POSITIVE_INFINITY]]
        (let [failure (outcome #(p {:n bad}))]
          (is (= :invalid-value (:siftell/error failure)))
          (is (= [:n] (:path failure)))
          (is (not (.contains (pr-str failure) "SECRET-CUSTOMER-DATA")))))))
  (is ((c/compile-condition {:path [:n] :op :gt :value 9007199254740992N}) {:n 9007199254740993N}))
  (doseq [spec [{:path [:x] :op :gt :value "2"}
               {:path [:x] :op :gt :value Double/NaN}
               {:path [:x] :op :between :value [3 1]}
               {:path [:x] :op :between :value [1]}
               {:path [:x] :op :in :value "abc"}
               {:path [:x] :op :eq}
               {:path [:x] :op :eq :value identity}
               {:path [:x] :op :present :value true}
               {:path [:x] :op :eq :value 1 :ignored true}
               {:path [:x] :op :unknown :value 1}
               {:path [:items -1] :op :missing}
               {:path '(:x) :op :present}
               {:all [] :any []} {:not nil} {:all {}}]]
    (is (= :invalid-condition (compile-error spec)) (pr-str (dissoc spec :value)))))

(deftest groups-short-circuit-without-evaluating-unneeded-values
  (let [bad {:path [:n] :op :gt :value 1}
        absent {:path [:missing] :op :present}]
    (is (false? ((c/compile-condition {:all [absent bad]}) {:n "not-numeric"})))
    (is (true? ((c/compile-condition {:any [{:not absent} bad]}) {:n "not-numeric"})))
    (is (true? ((c/compile-condition {:all []}) {})))
    (is (false? ((c/compile-condition {:any []}) {})))
    (is (false? ((c/compile-condition {:not {:all []}}) {})))))

(deftest explicit-nested-quantifiers
  (let [p (c/compile-condition {:path [:orders] :op :some
                                :where {:path [:items] :op :every
                                        :where {:all [{:path [:active] :op :eq :value true}
                                                      {:path [:quantity] :op :gt :value 0}]}}})]
    (is (true? (p {:orders [{:items [{:active false :quantity 1}]}
                            {:items [{:active true :quantity 2} {:active true :quantity 3}]}]})))
    (is (false? (p {:orders [{:items [{:active true :quantity 1} {:active false :quantity 3}]}]})))
    (is (false? (p {})))
    (is (false? (p {:orders []}))))
  (is (true? ((c/compile-condition {:path [:x] :op :every :where {:path [] :op :gt :value 0}}) {:x []})))
  (is (true? ((c/compile-condition {:path [:x] :op :some :where {:path [] :op :nil?}}) {:x [false nil]})))
  (is (true? ((c/compile-condition {:path [:x] :op :some :where {:path [] :op :gt :value 0}}) {:x [1 "unvisited"]})))
  (is (false? ((c/compile-condition {:path [:x] :op :every :where {:path [] :op :gt :value 0}}) {:x [0 "unvisited"]})))
  (doseq [actual [nil "abc" #{} {} 1]]
    (is (= :invalid-value (:siftell/error (outcome #((c/compile-condition {:path [:x] :op :some :where {:all []}}) {:x actual})))))))

(deftest contains-keeps-collection-and-string-orientation
  (is ((c/compile-condition {:path [:x] :op :contains :value "ab"}) {:x "zabc"}))
  (is ((c/compile-condition {:path [:x] :op :contains :value {:id 1}}) {:x [{:id 0} {:id 1}]}))
  (is ((c/compile-condition {:path [:x] :op :contains :value nil}) {:x #{nil false}}))
  (is (false? ((c/compile-condition {:path [:x] :op :contains :value 1}) {:x [1M]})))
  (doseq [actual [{:key 1} nil false 1]]
    (is (= :invalid-value (:siftell/error (outcome #((c/compile-condition {:path [:x] :op :contains :value :key}) {:x actual})))))))
