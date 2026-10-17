(ns siftell.examples
  "Executable examples. Every scenario checks actual selection and rendered output."
  (:require [siftell.examples.collections :as collections]
            [siftell.examples.delivery :as delivery]
            [siftell.examples.diagnostics :as diagnostics]
            [siftell.examples.eligibility :as eligibility]
            [siftell.examples.registries :as registries]
            [siftell.examples.workflow :as workflow]))

(def scenarios
  [[:delivery delivery/checks]
   [:workflow workflow/checks]
   [:eligibility eligibility/checks]
   [:collections collections/checks]
   [:diagnostics diagnostics/checks]
   [:registries registries/checks]])

(defn -main [& _]
  (let [total
        (reduce
          (fn [total [scenario checks]]
            (let [checks (checks)]
              (doseq [{:keys [label expected actual]} checks]
                (when-not (= expected actual)
                  (throw (ex-info (str "Example failed: " (name scenario) " / " label)
                                  {:scenario scenario :expected expected :actual actual}))))
              (println (str (name scenario) ": " (count checks) " checks passed"))
              (+ total (count checks))))
          0 scenarios)]
    (println (str (count scenarios) " examples, " total " checks passed."))))
