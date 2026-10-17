(ns siftell.examples.support)

(defn check [label expected actual]
  {:label label :expected expected :actual actual})

(defn error-data [f]
  (try
    (f)
    {:siftell/error :not-thrown}
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn provenance-checks [selection result]
  (let [definition-id (get-in selection [:provenance :definition-id])]
    [(check "definition identity is a SHA-256 string"
            true (boolean (and (string? definition-id)
                               (re-matches #"[0-9a-f]{64}" definition-id))))
     (check "selection and rendering identify the same definition"
            definition-id (get-in result [:provenance :definition-id]))
     (check "render provenance records matching rules"
            (:rule-ids selection) (get-in result [:provenance :rule-ids]))]))
