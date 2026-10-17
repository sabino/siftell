(ns siftell.examples.delivery
  "Compose a notification from ordered text and structured content."
  (:require [siftell.api :as s]
            [siftell.examples.support :as support]))

(def definition
  {:schema
   [:map
    [:recipient [:map [:name :string] [:channel [:enum :email :sms]]]]
    [:delivery [:map [:tracking-id :string] [:status [:enum :on-time :delayed]]
                [:delay-hours :int]]]]
   :conditions
   {:delayed {:path [:delivery :status] :op :eq :value :delayed}
    :long-delay {:path [:delivery :delay-hours] :op :gte :value 4}
    :email {:path [:recipient :channel] :op :eq :value :email}}
   :fragments
   {:subject {:template "An update on delivery {{delivery.tracking-id}}"
              :slot :subject :attributes {:channel :email}}
    :body {:template "Hi {{recipient.name}}, your delivery is delayed by {{delivery.delay-hours}} hours."
           :slot :body :attributes {:tone :informative}}
    :action {:value {:action :view-tracking :label "Track delivery"}
             :slot :action :attributes {:channel :email}}}
   :rules
   [{:id :email-delay
     :when [:all :delayed :long-delay :email]
     :emit [:subject :body :action]}]})

(def input
  {:recipient {:name "Mara" :channel :email}
   :delivery {:tracking-id "PKG-204" :status :delayed :delay-hours 6}})

(def expected-fragments
  [{:id :subject :text "An update on delivery PKG-204"
    :slot :subject :attributes {:channel :email}}
   {:id :body :text "Hi Mara, your delivery is delayed by 6 hours."
    :slot :body :attributes {:tone :informative}}
   {:id :action :value {:action :view-tracking :label "Track delivery"}
    :slot :action :attributes {:channel :email}}])

(defn run []
  (let [plan (s/compile definition)]
    {:selection (s/select plan input)
     :result (s/render plan input)}))

(defn checks []
  (let [{:keys [selection result]} (run)]
    (into
      [(support/check "delivery rules" [:email-delay] (:rule-ids selection))
       (support/check "delivery fragment order" [:subject :body :action] (:fragment-ids selection))
       (support/check "delivery text and structured action" expected-fragments (:fragments result))
       (support/check "delivery diagnostics" [] (:diagnostics result))]
      (support/provenance-checks selection result))))
