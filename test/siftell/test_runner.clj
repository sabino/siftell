(ns siftell.test-runner
  (:require [clojure.test :as t]
            [siftell.engine-test]
            [siftell.conditions-test]
            [siftell.templates-test]))

(defn -main [& _]
  (let [result (t/run-tests 'siftell.engine-test 'siftell.conditions-test
                            'siftell.templates-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
