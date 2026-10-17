(ns siftell.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [siftell.examples :as examples]))

(deftest executable-examples
  (doseq [[scenario checks] examples/scenarios]
    (testing (name scenario)
      (doseq [{:keys [label expected actual]} (checks)]
        (testing label
          (is (= expected actual)))))))
