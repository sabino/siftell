(ns siftell.examples.collections
  "Test nested collections without projecting or truncating template input."
  (:require [siftell.api :as s]
            [siftell.examples.support :as support]))

(def definition
  {:schema
   [:map [:orders
          [:vector [:map [:id :string]
                    [:items [:vector [:map [:name :string] [:quantity :int]
                                      [:backordered :boolean]]]]]]]]
   :conditions
   {:has-backorder
    {:path [:orders] :op :some
     :where {:path [:items] :op :some
             :where {:path [:backordered] :op :eq :value true}}}
    :valid-quantities
    {:path [:orders] :op :every
     :where {:path [:items] :op :every
             :where {:path [:quantity] :op :gt :value 0}}}}
   :fragments
   {:review
    {:template "{% for order in orders %}{{order.id}}: {% for item in order.items %}{{item.name}} x{{item.quantity}}{% if item.backordered %} (backordered){% endif %}{% if not forloop.last %}, {% endif %}{% endfor %}{% if not forloop.last %}; {% endif %}{% endfor %}."
     :slot :body :attributes {:audience :fulfillment}}}
   :rules
   [{:id :backorder-review :when [:all :has-backorder :valid-quantities] :emit [:review]}]})

(def input
  {:orders
   [{:id "A101" :items [{:name "Notebook" :quantity 1 :backordered false}]}
    {:id "B202" :items [{:name "Pen" :quantity 2 :backordered false}
                       {:name "Marker" :quantity 1 :backordered true}]}]})

(defn run []
  (let [plan (s/compile definition)]
    {:selection (s/select plan input)
     :result (s/render plan input)
     :empty-selection (s/select plan {:orders []})}))

(defn checks []
  (let [{:keys [selection result empty-selection]} (run)]
    [(support/check "a nested match in the second order and second item"
                    [:backorder-review] (:rule-ids selection))
     (support/check "loops retain every original item and restore outer loop metadata"
                    "A101: Notebook x1; B202: Pen x2, Marker x1 (backordered)."
                    (get-in result [:fragments 0 :text]))
     (support/check "no element satisfies some in an empty collection"
                    [] (:fragment-ids empty-selection))
     (support/check "collection rendering diagnostics" [] (:diagnostics result))]))
