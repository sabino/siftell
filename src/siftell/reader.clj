(ns siftell.reader
  "Strict, bounded reading of one EDN definition without evaluation or I/O."
  (:require [clojure.edn :as edn])
  (:import (java.io PushbackReader StringReader)))

(defn read-definition
  "Read exactly one EDN map from text. Standard #uuid and #inst are supported.
  :max-chars defaults to 4 Mi characters; caller owns file or network I/O."
  ([text] (read-definition text {}))
  ([text {:keys [max-chars] :or {max-chars (* 4 1024 1024)}}]
   (when-not (and (string? text) (pos-int? max-chars))
     (throw (ex-info "Expected EDN text and a positive :max-chars limit"
                     {:siftell/error :invalid-edn})))
   (when (> (count text) max-chars)
     (throw (ex-info "EDN text exceeds the configured size limit"
                     {:siftell/error :definition-too-large :max-chars max-chars})))
   (try
     (with-open [reader (PushbackReader. (StringReader. text))]
       (let [eof (Object.)
             options {:eof eof :readers {}
                      :default (fn [_ _] (throw (ex-info "Unsupported EDN tag" {})))}
             value (edn/read options reader)]
         (when-not (and (map? value) (identical? eof (edn/read options reader)))
           (throw (ex-info "Expected exactly one EDN map" {})))
         value))
     (catch Exception _
       (throw (ex-info "Expected exactly one valid EDN definition map"
                       {:siftell/error :invalid-edn}))))))
