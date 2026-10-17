(ns siftell.compiler
  "Definition validation and immutable execution-plan construction."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [malli.core :as m]
            [siftell.conditions :as conditions]
            [siftell.data :as data]
            [siftell.templates :as templates])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(defrecord Plan [definition definition-id validate explain predicates rules fragments template-errors])

(defn plan? "True for a compiled execution plan." [x] (instance? Plan x))

(defn- invalid! [message path]
  (throw (ex-info message {:siftell/error :invalid-definition :path path})))

(defn- id? [x] (or (keyword? x) (and (string? x) (not (str/blank? x)))))

(defn- digest [definition]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-dup* false *print-readably* true]
    (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (pr-str [:siftell/definition-v1 (data/canonical definition)])
                                    StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" (bit-and 255 %)) bytes)))))

(defn- known-keys! [value allowed path]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (invalid! (str "Unknown definition keys: " (pr-str (set unknown))) path)))

(defn- at-definition-path [path f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info (.getMessage e) (assoc (ex-data e) :definition-path path))))))

(defn- compile-expression [expression condition-ids path]
  (cond
    (boolean? expression) (constantly expression)
    (id? expression)
    (if (contains? condition-ids expression)
      (fn [lookup] (lookup expression))
      (invalid! "Rule references an undefined condition" path))
    (vector? expression)
    (let [[op & args] expression
          children (mapv (fn [i arg] (compile-expression arg condition-ids (conj path (inc i))))
                         (range) args)]
      (case op
        :all (fn [lookup] (every? #(% lookup) children))
        :any (fn [lookup] (boolean (some #(% lookup) children)))
        :not (if (= 1 (count children))
               (fn [lookup] (not ((first children) lookup)))
               (invalid! "The :not expression requires exactly one operand" path))
        (invalid! "Rule expression must use :all, :any or :not" path)))
    :else (invalid! "Rule :when must be a condition ID, boolean or logical vector" path)))

(defn- compile-fragment [[id fragment]]
  (let [path [:fragments id]]
    (when-not (id? id) (invalid! "Fragment IDs must be keywords or nonblank strings" path))
    (when-not (map? fragment) (invalid! "A fragment must be a map" path))
    (known-keys! fragment #{:template :value :slot :attributes} path)
    (when (= (contains? fragment :template) (contains? fragment :value))
      (invalid! "A fragment requires exactly one of :template or :value" path))
    (when (and (contains? fragment :attributes) (not (map? (:attributes fragment))))
      (invalid! "Fragment :attributes must be a map" (conj path :attributes)))
    [id (cond-> (assoc fragment ::copy-output? (data/contains-instant? fragment))
          (contains? fragment :template)
          (assoc ::template
                 (at-definition-path (conj path :template)
                                     #(templates/compile-template (:template fragment)))))]))

(defn compile
  "Validate an EDN definition and compile its schema, conditions and templates once.
  Options: :schema-options passed to Malli; :template-errors :omit (default) or :throw."
  ([definition] (compile definition {}))
  ([definition options]
   (when-not (map? definition) (invalid! "A definition must be an EDN map" []))
   (when-not (data/declarative? definition) (invalid! "Definitions must contain only finite EDN data" []))
   (known-keys! definition #{:schema :match :conditions :fragments :rules} [])
   (when-not (map? options) (invalid! "Compilation options must be a map" [:options]))
   (known-keys! options #{:schema-options :template-errors} [:options])
   (when-not (or (nil? (:schema-options options)) (map? (:schema-options options)))
     (invalid! "The :schema-options option must be a map" [:options :schema-options]))
   (when-not (contains? #{:all :first} (get definition :match :all))
     (invalid! "Definition :match must be :all or :first" [:match]))
   (when-not (contains? #{:omit :throw} (get options :template-errors :omit))
     (invalid! "The :template-errors option must be :omit or :throw" [:options :template-errors]))
   (doseq [key [:conditions :fragments]]
     (when-not (map? (get definition key {})) (invalid! (str key " must be a map") [key])))
   (when-not (vector? (:rules definition)) (invalid! "Definition :rules must be an ordered vector" [:rules]))
   (let [definition (data/snapshot definition)
         condition-defs (get definition :conditions {})
         predicates (into {} (map (fn [[id spec]]
                                   (when-not (id? id)
                                     (invalid! "Condition IDs must be keywords or nonblank strings" [:conditions id]))
                                   [id (at-definition-path [:conditions id]
                                                           #(conditions/compile-condition spec))])) condition-defs)
         fragments (into {} (map compile-fragment) (get definition :fragments {}))
         condition-ids (set (keys predicates))
         rules (mapv (fn [index rule]
                       (let [path [:rules index]]
                         (when-not (map? rule) (invalid! "A rule must be a map" path))
                         (known-keys! rule #{:id :when :emit} path)
                         (when-not (id? (:id rule)) (invalid! "A rule requires an ID" (conj path :id)))
                         (when-not (and (vector? (:emit rule)) (every? #(contains? fragments %) (:emit rule)))
                           (invalid! "Rule :emit must be a vector of defined fragment IDs" (conj path :emit)))
                         (assoc rule ::predicate
                                (compile-expression (get rule :when true) condition-ids
                                                    (conj path :when)))))
                     (range) (:rules definition))
         _ (when-not (= (count rules) (count (set (map :id rules))))
             (invalid! "Rule IDs must be unique" [:rules]))
         schema (try (m/schema (get definition :schema :any)
                               (assoc (:schema-options options) ::m/disable-sci true))
                     (catch Exception _ (invalid! "The input schema is invalid" [:schema])))]
     (->Plan definition (digest definition) (m/validator schema) (m/explainer schema)
             predicates rules fragments (get options :template-errors :omit)))))
