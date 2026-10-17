(ns siftell.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [siftell.api :as s]
            [siftell.conditions :as conditions]))

(def definition
  {:schema [:map [:name :string] [:age :int] [:tags [:vector :keyword]]]
   :conditions {:adult {:path [:age] :op :gte :value 18}
                :member {:path [:tags] :op :contains :value :member}}
   :fragments {:hello {:template "Hello {{name}}" :slot :body :attributes {:language :en}}
               :offer {:value {:discount 10} :slot :offer}}
   :rules [{:id :welcome :when :adult :emit [:hello]}
           {:id :benefits :when [:all :adult :member] :emit [:hello :offer]}]})

(def input {:name "Avery" :age 24 :tags [:member :staff]})

(defn error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest ordered-selection-and-rendering
  (let [plan (s/compile definition)
        selection (s/select plan input)
        output (s/render plan input)]
    (is (= [:welcome :benefits] (:rule-ids selection)))
    (is (= [:hello :offer] (:fragment-ids selection)))
    (is (= [{:id :hello :text "Hello Avery" :slot :body :attributes {:language :en}}
            {:id :offer :value {:discount 10} :slot :offer}]
           (:fragments output)))
    (is (empty? (:diagnostics output)))
    (is (= [:welcome :benefits] (get-in output [:provenance :rule-ids])))
    (is (re-matches #"[0-9a-f]{64}" (get-in output [:provenance :definition-id])))
    (is (= {:match :all :conditions 2 :rules 2 :fragments 2}
           (dissoc (s/describe plan) :definition-id)))
    (is (= [] (:fragment-ids (s/select plan (assoc input :age 17)))))))

(deftest explicit-first-match
  (let [plan (s/compile (assoc definition :match :first))]
    (is (= [:welcome] (:rule-ids (s/select plan input))))
    (is (= [:hello] (:fragment-ids (s/select plan input))))))

(deftest full-input-schema-validation
  (let [plan (s/compile definition)
        invalid (assoc input :tags [:member 42])
        data (error-data #(s/render plan invalid))]
    (is (= :invalid-input (:siftell/error data)))
    (is (some #(= [:tags 1] (:in %)) (:errors data)))
    (is (not (contains? data :value)))
    (is (not (.contains (pr-str data) "Avery")))
    (is (= :invalid-input (:siftell/error (error-data #(s/select plan (dissoc input :name))))))))

(deftest schema-registries-and-typed-edn
  (let [uuid #uuid "a02708e1-ed25-481a-a78f-adc8276e5dc5"
        plan (s/compile {:schema [:schema {:registry {::name :string}}
                                 [:map [:id :uuid] [:name ::name]]]
                         :fragments {:identity {:value uuid}}
                         :rules [{:id :emit :emit [:identity]}]})]
    (is (= uuid (get-in (s/render plan {:id uuid :name "Jo"}) [:fragments 0 :value])))
    (is (= :invalid-input (:siftell/error (error-data #(s/render plan {:id "uuid" :name "Jo"})))))))

(deftest stable-content-identity
  (let [id #(-> % s/compile s/describe :definition-id)
        reordered (into (sorted-map) definition)]
    (is (= (id definition) (id reordered)))
    (is (= (id definition) (binding [*print-length* 1 *print-level* 1] (id definition))))
    (is (not= (id definition) (id (update definition :rules #(vec (reverse %))))))))

(deftest definitions-own-instant-snapshots
  (let [instant (java.util.Date. 0)
        plan (s/compile {:conditions {:same {:path [:date] :op :eq :value instant}}
                         :fragments {:date {:value {:date instant} :attributes {:at instant}}}
                         :rules [{:id :date :when :same :emit [:date]}]})
        identity (:definition-id (s/describe plan))]
    (.setTime instant 1000)
    (let [result (s/render plan {:date (java.util.Date. 0)})]
      (is (= 0 (.getTime ^java.util.Date (get-in result [:fragments 0 :value :date]))))
      (.setTime ^java.util.Date (get-in result [:fragments 0 :value :date]) 2000)
      (.setTime ^java.util.Date (get-in result [:fragments 0 :attributes :at]) 3000))
    (let [result (s/render plan {:date (java.util.Date. 0)})]
      (is (= 0 (.getTime ^java.util.Date (get-in result [:fragments 0 :value :date]))))
      (is (= 0 (.getTime ^java.util.Date (get-in result [:fragments 0 :attributes :at])))))
    (is (= identity (:definition-id (s/describe plan))))))

(deftest identity-preserves-scalar-types-and-rejects-non-edn-values
  (let [definition-for (fn [value] {:fragments {:value {:value value}}
                                   :rules [{:id :all :emit [:value]}]})
        id #(-> % definition-for s/compile s/describe :definition-id)
        values [nil false "nil" 'nil-value :nil \n 1 1.0 1M #uuid "a02708e1-ed25-481a-a78f-adc8276e5dc5"]]
    (is (= (count values) (count (set (map id values)))))
    (doseq [value [(symbol "nil") (symbol "a b") (keyword "")
                   (java.util.concurrent.atomic.AtomicInteger. 1)
                   (java.sql.Timestamp. 0)]]
      (is (= :invalid-definition
             (:siftell/error (error-data #(s/compile (definition-for value)))))))))

(deftest one-schema-validation-per-invocation
  (let [counter (atom 0)
        typed (assoc definition :schema ::counted)
        plan (s/compile typed {:schema-options
                               {:registry (assoc (m/default-schemas) ::counted
                                                 [:fn (fn [value] (swap! counter inc) (map? value))])}})]
    (is (= 0 @counter))
    (s/render plan input)
    (is (= 1 @counter))
    (s/select plan input)
    (is (= 2 @counter))))

(deftest condition-results-are-request-local-and-lazy
  (let [evaluations (atom [])
        original conditions/compile-condition
        plan (with-redefs [conditions/compile-condition
                           (fn [spec]
                             (let [predicate (original spec)]
                               (fn [value] (swap! evaluations conj (:path spec)) (predicate value))))]
               (s/compile (assoc-in definition [:conditions :unused]
                                    {:path [:unused] :op :eq :value 1})))]
    (s/render plan input)
    (is (= [[:age] [:tags]] @evaluations))
    (reset! evaluations [])
    (s/render plan (assoc input :age 10))
    (is (= [[:age]] @evaluations))))

(deftest concurrency-and-reuse
  (let [plan (s/compile definition)
        jobs (mapv (fn [n] (future (s/render plan (assoc input :name (str n))))) (range 64))]
    (is (= (mapv #(str "Hello " %) (range 64))
           (mapv #(get-in @% [:fragments 0 :text]) jobs)))
    (is (= (s/render plan input) (s/render plan input)))))

(deftest strict-reader
  (let [text "{:id #uuid \"a02708e1-ed25-481a-a78f-adc8276e5dc5\" :date #inst \"2026-01-01\"}"]
    (is (uuid? (:id (s/read-definition text))))
    (is (inst? (:date (s/read-definition text)))))
  (doseq [text ["" "nil" "[]" "{} {}" "{} nil" "{:x #unknown 1}" "#=(println :oops)" "{:x :}"]]
    (is (= :invalid-edn (:siftell/error (error-data #(s/read-definition text)))) text))
  (is (= {} (s/read-definition "{} ; trailing comment")))
  (is (= :definition-too-large (:siftell/error (error-data #(s/read-definition "{}" {:max-chars 1}))))))

(deftest invalid-definitions-are-actionable
  (doseq [bad [(assoc definition :wat true)
               (assoc definition :rules (seq (:rules definition)))
               (assoc definition :match :random)
               (assoc-in definition [:rules 0 :when] :absent)
               (assoc-in definition [:rules 0 :emit] [:absent])
               (assoc-in definition [:rules 1 :id] :welcome)
               (assoc-in definition [:rules 0 :when] [:not true false])
               (assoc-in definition [:fragments :offer :template] "ambiguous")
               (assoc-in definition [:fragments :offer :value] (fn [] :not-data))
               (assoc definition :schema [:fn '(fn [_] true)])]]
    (is (= :invalid-definition (:siftell/error (error-data #(s/compile bad)))))))

(deftest empty-and-string-identifiers
  (let [plan (s/compile {:rules []})]
    (is (= [] (:fragments (s/render plan nil)))))
  (let [plan (s/compile {:conditions {"ready" {:path [:ready] :op :eq :value true}}
                         :fragments {"a" {:value nil}}
                         :rules [{:id "yes" :when [:any false "ready"] :emit ["a" "a"]}]})]
    (is (= [{:id "a" :value nil}] (:fragments (s/render plan {:ready true}))))))

(deftest selected-template-error-policy
  (let [definition {:fragments {:good {:value 7} :bad {:template "{{object}}"}}
                    :rules [{:id :all :emit [:bad :good]}]}
        result (s/render (s/compile definition) {:object {:x 1}})]
    (is (= [{:id :good :value 7}] (:fragments result)))
    (is (= :template-render-failed (get-in result [:diagnostics 0 :code])))
    (is (= :bad (get-in result [:diagnostics 0 :fragment-id])))
    (is (= :template-render-failed
           (:siftell/error (error-data #(s/render (s/compile definition {:template-errors :throw})
                                                 {:object {:x 1}})))))))
