(ns siftell.examples.diagnostics
  "Inspect structured failures and selected-template diagnostics."
  (:require [siftell.api :as s]
            [siftell.examples.support :as support]))

(def definition
  {:schema [:map [:customer [:map [:name :string]]]]
   :fragments
   {:object {:template "{{customer}}" :slot :body :attributes {}}
    :greeting {:template "Hello {{customer.name}}." :slot :body :attributes {}}}
   :rules [{:id :welcome :when true :emit [:object :greeting]}]})

(def input {:customer {:name "Mara"}})

(defn run []
  (let [plan (s/compile definition)
        strict-plan (s/compile definition {:template-errors :throw})]
    {:selection (s/select plan input)
     :result (s/render plan input)
     :input-error (support/error-data #(s/render plan {:customer {:name 42}}))
     :definition-error (support/error-data
                         #(s/compile (assoc-in definition [:rules 0 :emit] [:unknown])))
     :strict-error (support/error-data #(s/render strict-plan input))}))

(defn checks []
  (let [{:keys [selection result input-error definition-error strict-error]} (run)]
    [(support/check "selection is independent of template rendering"
                    [:object :greeting] (:fragment-ids selection))
     (support/check "a failing selected template does not suppress another fragment"
                    [{:id :greeting :text "Hello Mara." :slot :body :attributes {}}]
                    (:fragments result))
     (support/check "render failure identifies its fragment"
                    [{:severity :error :code :template-render-failed :fragment-id :object}]
                    (mapv #(select-keys % [:severity :code :fragment-id]) (:diagnostics result)))
     (support/check "input schema violations are errors"
                    :invalid-input (:siftell/error input-error))
     (support/check "unknown fragment references are definition errors"
                    :invalid-definition (:siftell/error definition-error))
     (support/check "strict template mode throws its specific error"
                    :template-render-failed (:siftell/error strict-error))]))
