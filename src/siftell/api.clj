(ns siftell.api
  "Compile declarative EDN into reusable plans; select or render against typed data.
  The caller owns persistence, input preparation and output delivery."
  (:refer-clojure :exclude [compile select])
  (:require [siftell.compiler :as compiler]
            [siftell.reader :as reader]
            [siftell.runtime :as runtime]))

(defn compile
  "Compile a definition once. Options: :schema-options (Malli options),
  :template-errors (:omit by default, or :throw). See doc/reference.md."
  ([definition] (compiler/compile definition))
  ([definition options] (compiler/compile definition options)))

(defn select
  "Return ordered :fragment-ids, matching :rule-ids and definition provenance.
  Validates the complete input before evaluating conditions."
  [plan input] (runtime/select plan input))

(defn render
  "Return ordered :fragments, :diagnostics and :provenance for a typed input.
  Templates see the original input, including all collection elements."
  [plan input] (runtime/render plan input))

(defn describe
  "Return serializable plan metadata without compiled functions."
  [plan]
  (when-not (compiler/plan? plan)
    (throw (ex-info "Expected a compiled Siftell plan" {:siftell/error :invalid-plan})))
  {:definition-id (:definition-id plan)
   :match (get-in plan [:definition :match] :all)
   :conditions (count (:predicates plan))
   :rules (count (:rules plan))
   :fragments (count (:fragments plan))})

(defn read-definition
  "Read one EDN definition map from text without evaluation. #uuid/#inst are valid.
  Optional :max-chars bounds text size (default 4 Mi characters)."
  ([text] (reader/read-definition text))
  ([text options] (reader/read-definition text options)))
