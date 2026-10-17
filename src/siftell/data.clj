(ns siftell.data
  "Definition data boundaries and stable, type-preserving identity."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import (java.util Date UUID)))

(def ^:private numeric-types
  #{Byte Short Integer Long Float Double java.math.BigInteger
    java.math.BigDecimal clojure.lang.BigInt clojure.lang.Ratio})

(defn- readable-name? [value]
  (try
    (binding [*print-meta* false *print-readably* true]
      (= value (edn/read-string (pr-str value))))
    (catch Exception _ false)))

(defn declarative?
  "True for finite EDN data; records, functions and mutable numeric objects fail."
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value) (char? value)
        (instance? UUID value) (= Date (class value))) true
    (or (keyword? value) (symbol? value)) (readable-name? value)
    (number? value) (and (contains? numeric-types (class value))
                        (not (and (or (float? value) (double? value))
                                  (not (Double/isFinite (double value))))))
    (record? value) false
    (map? value) (every? (fn [[k v]] (and (declarative? k) (declarative? v))) value)
    (or (vector? value) (list? value) (set? value)) (every? declarative? value)
    :else false))

(defn contains-instant?
  "Whether a value contains a mutable EDN instant requiring boundary copies."
  [value]
  (or (= Date (class value))
      (and (coll? value) (boolean (some contains-instant? value)))))

(defn snapshot
  "Detach EDN instant leaves and discard metadata outside the authored data."
  [value]
  (walk/postwalk #(cond (= Date (class %)) (Date. (.getTime ^Date %))
                       (instance? clojure.lang.IObj %) (with-meta % nil)
                       :else %) value))

(defn canonical
  "Encode containers and scalar types explicitly, independently of map ordering."
  [value]
  (cond
    (map? value) [:map (->> value (map (fn [[k v]] [(canonical k) (canonical v)]))
                           (sort-by (comp pr-str first)) vec)]
    (set? value) [:set (->> value (map canonical) (sort-by pr-str) vec)]
    (vector? value) [:vector (mapv canonical value)]
    (list? value) [:list (mapv canonical value)]
    (nil? value) [:nil]
    (boolean? value) [:boolean value]
    (string? value) [:string value]
    (char? value) [:character (int value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (number? value) [:number (.getName (class value)) (str value)]
    (instance? UUID value) [:uuid (str value)]
    (= Date (class value)) [:instant (.getTime ^Date value)]))
