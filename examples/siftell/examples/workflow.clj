(ns siftell.examples.workflow
  "Choose one message by ordered rule precedence."
  (:require [siftell.api :as s]
            [siftell.examples.support :as support]))

(def definition
  {:schema
   [:map [:request [:map [:id :string] [:status [:enum :blocked :ready]]
                    [:priority [:enum :normal :urgent]]
                    [:owner {:optional true} [:maybe :string]]]]]
   :match :first
   :conditions
   {:blocked {:path [:request :status] :op :eq :value :blocked}
    :urgent {:path [:request :priority] :op :eq :value :urgent}}
   :fragments
   {:blocked {:template "Request {{request.id}} needs attention before it can proceed."
              :slot :status :attributes {:state :blocked}}
    :urgent {:template "Request {{request.id}} is next in the priority queue."
             :slot :status :attributes {:state :priority}}
    :queued {:template "Request {{request.id}} is queued{% if request.owner %} for {{request.owner}}{% else %} for assignment{% endif %}."
             :slot :status :attributes {:state :queued}}}
   :rules
   [{:id :blocked-first :when :blocked :emit [:blocked]}
    {:id :urgent-next :when :urgent :emit [:urgent]}
    {:id :otherwise :when true :emit [:queued]}]})

(def inputs
  [{:request {:id "REQ-81" :status :blocked :priority :urgent}}
   {:request {:id "REQ-82" :status :ready :priority :normal :owner "Dev"}}
   {:request {:id "REQ-83" :status :ready :priority :normal}}])

(defn run []
  (let [plan (s/compile definition)]
    (mapv (fn [input] {:selection (s/select plan input)
                       :result (s/render plan input)})
          inputs)))

(defn checks []
  (let [[blocked assigned unassigned] (run)]
    [(support/check "first matching rule wins even when later rules match"
                    [:blocked-first] (get-in blocked [:selection :rule-ids]))
     (support/check "blocked message"
                    [{:id :blocked :text "Request REQ-81 needs attention before it can proceed."
                      :slot :status :attributes {:state :blocked}}]
                    (get-in blocked [:result :fragments]))
     (support/check "explicit fallback rule" [:otherwise]
                    (get-in assigned [:selection :rule-ids]))
     (support/check "if branch"
                    "Request REQ-82 is queued for Dev."
                    (get-in assigned [:result :fragments 0 :text]))
     (support/check "else branch for an optional missing field"
                    "Request REQ-83 is queued for assignment."
                    (get-in unassigned [:result :fragments 0 :text]))]))
