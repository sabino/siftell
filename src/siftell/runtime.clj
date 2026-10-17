(ns siftell.runtime
  "Request-local selection and rendering of immutable plans."
  (:refer-clojure :exclude [select])
  (:require [siftell.compiler :as compiler]
            [siftell.data :as data]
            [siftell.templates :as templates]))

(defn- require-plan! [plan]
  (when-not (compiler/plan? plan)
    (throw (ex-info "Expected a compiled Siftell plan" {:siftell/error :invalid-plan}))))

(defn- validate! [plan input]
  (when-not ((:validate plan) input)
    (let [errors (:errors ((:explain plan) input))]
      (throw (ex-info "Input does not satisfy the definition schema"
                      {:siftell/error :invalid-input
                       :errors (mapv #(select-keys % [:path :in :type]) errors)})))))

(defn select
  "Validate input, select matching rules in order, and deduplicate emissions by ID.
  Named conditions are evaluated at most once per invocation, when first needed."
  [plan input]
  (require-plan! plan)
  (validate! plan input)
  (let [cache (volatile! {})
        lookup (fn [id]
                 (let [cached @cache]
                   (if (contains? cached id)
                     (get cached id)
                     (let [result (try (boolean ((get (:predicates plan) id) input))
                                       (catch clojure.lang.ExceptionInfo e
                                         (throw (ex-info (.getMessage e)
                                                         (assoc (ex-data e) :condition-id id)))))]
                       (vswap! cache assoc id result)
                       result))))
        first? (= :first (get-in plan [:definition :match]))]
    (loop [remaining (:rules plan), rule-ids [], fragment-ids [], emitted #{}]
      (if-let [rule (first remaining)]
        (if ((::compiler/predicate rule) lookup)
          (let [[ids seen] (reduce (fn [[ids seen] id]
                                     (if (contains? seen id) [ids seen]
                                         [(conj ids id) (conj seen id)]))
                                   [fragment-ids emitted] (:emit rule))
                selected (conj rule-ids (:id rule))]
            (if first?
              {:fragment-ids ids :rule-ids selected :provenance {:definition-id (:definition-id plan)}}
              (recur (next remaining) selected ids seen)))
          (recur (next remaining) rule-ids fragment-ids emitted))
        {:fragment-ids fragment-ids :rule-ids rule-ids
         :provenance {:definition-id (:definition-id plan)}}))))

(defn render
  "Select and render fragments. Failed templates are omitted with diagnostics by
  default; compile with {:template-errors :throw} to fail the call instead."
  [plan input]
  (let [{:keys [fragment-ids rule-ids provenance]} (select plan input)
        result
        (reduce
         (fn [result id]
           (let [stored (get (:fragments plan) id)
                 fragment (if (::compiler/copy-output? stored) (data/snapshot stored) stored)
                 metadata (select-keys fragment [:slot :attributes])]
             (if (contains? fragment :value)
               (update result :fragments conj (assoc metadata :id id :value (:value fragment)))
               (try
                 (let [text (templates/render-template (::compiler/template fragment) input)]
                   (update result :fragments conj (assoc metadata :id id :text text)))
                 (catch Exception _
                   (let [data {:siftell/error :template-render-failed :fragment-id id}]
                     (if (= :throw (:template-errors plan))
                       (throw (ex-info "A selected fragment could not be rendered" data))
                       (update result :diagnostics conj
                               {:severity :error :code :template-render-failed
                                :fragment-id id :message "A selected fragment could not be rendered"}))))))))
         {:fragments [] :diagnostics []} fragment-ids)]
    (assoc result :provenance (assoc provenance :rule-ids rule-ids))))
