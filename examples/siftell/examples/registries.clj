(ns siftell.examples.registries
  "Compile a caller-owned registry once and reuse the immutable plan."
  (:require [malli.core :as m]
            [siftell.api :as s]
            [siftell.examples.support :as support]))

(def registry
  {:customer/name [:string {:min 1}]
   :customer/plan [:enum :standard :premium]
   :customer/profile [:map [:name [:ref :customer/name]]
                      [:plan [:ref :customer/plan]]]})

(def definition
  {:schema [:map [:customer [:ref :customer/profile]]]
   :match :first
   :conditions {:premium {:path [:customer :plan] :op :eq :value :premium}}
   :fragments
   {:premium {:template "Welcome {{customer.name|upper}}. Premium support is included."
              :slot :welcome :attributes {:plan :premium}}
    :standard {:template "Welcome {{customer.name|upper}}. Your account is ready."
               :slot :welcome :attributes {:plan :standard}}}
   :rules [{:id :premium :when :premium :emit [:premium]}
           {:id :standard :when true :emit [:standard]}]})

(def inputs
  [{:customer {:name "Rin" :plan :premium}}
   {:customer {:name "Lee" :plan :standard}}
   {:customer {:name "Jo" :plan :premium}}])

(defn run []
  (let [options {:schema-options {:registry (merge (m/default-schemas) registry)}}
        plan (s/compile definition options)]
    {:results (mapv #(s/render plan %) inputs)
     :repeated (s/render plan (first inputs))
     :invalid (support/error-data #(s/render plan {:customer {:name "" :plan :premium}}))}))

(defn checks []
  (let [{:keys [results repeated invalid]} (run)]
    [(support/check "one plan renders several typed inputs"
                    ["Welcome RIN. Premium support is included."
                     "Welcome LEE. Your account is ready."
                     "Welcome JO. Premium support is included."]
                    (mapv #(get-in % [:fragments 0 :text]) results))
     (support/check "the same plan and input produce the same result" (first results) repeated)
     (support/check "the custom name schema remains active"
                    :invalid-input (:siftell/error invalid))
     (support/check "all renders identify the same definition"
                    1 (count (set (map #(get-in % [:provenance :definition-id]) results))))]))
