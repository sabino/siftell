(ns siftell.examples.eligibility
  "Collect every applicable workshop explanation in authored order."
  (:require [siftell.api :as s]
            [siftell.examples.support :as support]))

(def definition
  {:schema
   [:map [:applicant [:map [:name :string] [:age :int]
                      [:member :boolean] [:volunteer-hours :int]]]]
   :match :all
   :conditions
   {:old-enough {:path [:applicant :age] :op :gte :value 16}
    :volunteer {:path [:applicant :volunteer-hours] :op :gte :value 2}
    :member {:path [:applicant :member] :op :eq :value true}}
   :fragments
   {:eligible {:template "{{applicant.name}}, you can join the weekend workshop."
               :slot :summary :attributes {:outcome :eligible}}
    :age {:value "The workshop is open to participants aged 16 and over."
          :slot :reason :attributes {:reason :age}}
    :volunteer {:value "Your volunteer hours qualify you for a reserved place."
                :slot :reason :attributes {:reason :volunteering}}
    :membership {:value "Your membership includes workshop access."
                 :slot :reason :attributes {:reason :membership}}}
   :rules
   [{:id :admission
     :when [:all :old-enough [:any :volunteer :member]]
     :emit [:eligible :age]}
    {:id :age-explanation :when :old-enough :emit [:age]}
    {:id :volunteer-explanation :when :volunteer :emit [:volunteer]}
    {:id :member-explanation :when :member :emit [:membership]}]})

(def input {:applicant {:name "Nora" :age 17 :member true :volunteer-hours 3}})

(defn run []
  (let [plan (s/compile definition)]
    {:selection (s/select plan input)
     :result (s/render plan input)}))

(defn checks []
  (let [{:keys [selection result]} (run)]
    [(support/check "all matching explanation rules"
                    [:admission :age-explanation :volunteer-explanation :member-explanation]
                    (:rule-ids selection))
     (support/check "first emission determines order and repeated IDs appear once"
                    [:eligible :age :volunteer :membership] (:fragment-ids selection))
     (support/check "rendered explanation order"
                    ["Nora, you can join the weekend workshop."
                     "The workshop is open to participants aged 16 and over."
                     "Your volunteer hours qualify you for a reserved place."
                     "Your membership includes workshop access."]
                    (mapv #(or (:text %) (:value %)) (:fragments result)))
     (support/check "a slot does not suppress other fragments"
                    [:summary :reason :reason :reason] (mapv :slot (:fragments result)))]))
