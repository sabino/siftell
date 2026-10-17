(ns siftell.templates
  "Deterministic inline text templates. No resource loader, clock, random source,
  custom tags, global extension registry, or implicit collection projection."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.util Locale Date]
           [java.time Instant]))

(def ^:private absent (Object.))
(deftype ^:private InlineTemplate [nodes])

(defn- reject! [kind message & [path]]
  (throw (ex-info message {:siftell/error kind :path (or path [])})))

(defn- quoted-split [text delimiter]
  (loop [index 0 start 0 quoted? false escaped? false parts []]
    (if (= index (count text))
      (do (when quoted? (reject! :invalid-template "A quoted template argument is unterminated."))
          (conj parts (subs text start index)))
      (let [ch (.charAt ^String text index)]
        (cond
          escaped? (recur (inc index) start quoted? false parts)
          (and quoted? (= ch \\)) (recur (inc index) start quoted? true parts)
          (= ch \") (recur (inc index) start (not quoted?) false parts)
          (and (not quoted?) (= ch delimiter)) (recur (inc index) (inc index) false false (conj parts (subs text start index)))
          :else (recur (inc index) start quoted? false parts))))))

(defn- map-value [value {:keys [text keyword]} path]
  (if (map? value)
    (let [string-key? (contains? value text) keyword-key? (contains? value keyword)]
      (cond
        (and string-key? keyword-key?)
        (reject! :invalid-value "A template path matches both a string and a keyword key." path)
        string-key? (get value text)
        keyword-key? (get value keyword)
        :else absent))
    absent))

(defn- path-reader [source]
  (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_-]*(?:\.(?:[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*" source)
    (reject! :invalid-template "Use a dotted template path with explicit collection indexes."))
  (let [parts (str/split source #"\.")
        steps (mapv (fn [part]
                      (if (re-matches #"[0-9]+" part)
                        (let [index (try (Long/parseLong part) (catch NumberFormatException _ nil))]
                          (when-not (and index (<= index Integer/MAX_VALUE))
                            (reject! :invalid-template "A template collection index is out of range."))
                          {:index (int index)})
                        {:text part :keyword (keyword part)})) parts)]
    (fn [input locals]
      (let [first-step (first steps)
            initial (if (contains? locals (:text first-step))
                      (get locals (:text first-step))
                      (map-value input first-step parts))]
        (loop [value initial index 1]
          (if (or (identical? absent value) (= index (count steps)))
            value
            (let [step (nth steps index)
                  next-value (if (contains? step :index)
                               (if (sequential? value) (nth value (:index step) absent) absent)
                               (map-value value step parts))]
              (recur next-value (inc index)))))))))

(defn- default-literal [source]
  (when-not (or (re-matches #"\"(?:[^\"\\]|\\.)*\"" source)
                (#{"nil" "true" "false"} source)
                (re-matches #"[+-]?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?M?" source))
    (reject! :invalid-template "Default requires a quoted string, boolean, nil, or numeric literal."))
  (try
    (let [value (edn/read-string source)]
      (when (and (double? value) (not (Double/isFinite value)))
        (reject! :invalid-template "A numeric default must be finite."))
      value)
    (catch Exception _ (reject! :invalid-template "The default literal is invalid."))))

(defn- compile-filter [source]
  (let [parts (mapv str/trim (quoted-split source \:)) name (first parts)]
    (case name
      "default" (do (when-not (= 2 (count parts))
                      (reject! :invalid-template "Default requires exactly one literal argument."))
                    (let [fallback (default-literal (second parts))]
                      (fn [value] (if (or (identical? absent value) (nil? value)) fallback value))))
      ("upper" "lower")
      (do (when-not (= 1 (count parts)) (reject! :invalid-template "Case filters do not take arguments."))
          (let [convert (if (= name "upper")
                          #(.toUpperCase ^String % Locale/ROOT)
                          #(.toLowerCase ^String % Locale/ROOT))]
            (fn [value]
              (cond (or (identical? absent value) (nil? value)) ""
                    (string? value) (convert value)
                    :else (reject! :invalid-value "A case filter requires a string value.")))))
      "count"
      (do (when-not (= 1 (count parts)) (reject! :invalid-template "Count does not take arguments."))
          (fn [value]
            (cond (or (identical? absent value) (nil? value)) 0
                  (or (string? value) (coll? value)) (count value)
                  :else (reject! :invalid-value "Count requires a string or collection value."))))
      (reject! :invalid-template "The template filter is unsupported."))))

(defn- compile-expression [source]
  (let [parts (mapv str/trim (quoted-split (str/trim source) \|))
        read-value (path-reader (first parts)) filters (mapv compile-filter (rest parts))]
    (fn [input locals] (reduce (fn [value filter-value] (filter-value value)) (read-value input locals) filters))))

(defn- truthy? [value]
  (and (not (identical? absent value)) (some? value) (not (false? value))
       (not (and (or (string? value) (coll? value)) (empty? value)))))

(defn- compile-test [source]
  (let [source (str/trim source) negated (re-matches #"(?s)not\s+(.+)" source)
        negate? (some? negated) expression (compile-expression (if negate? (second negated) source))]
    (fn [input locals] (let [result (boolean (truthy? (expression input locals)))] (if negate? (not result) result)))))

(defn- closing-position [text start delimiter]
  (loop [index start quoted? false escaped? false]
    (if (>= index (count text))
      (reject! :invalid-template "A template expression or tag is unterminated.")
      (let [ch (.charAt ^String text index)]
        (cond
          escaped? (recur (inc index) quoted? false)
          (and quoted? (= ch \\)) (recur (inc index) quoted? true)
          (= ch \") (recur (inc index) (not quoted?) false)
          (and (not quoted?) (.startsWith ^String text ^String delimiter index)) index
          :else (recur (inc index) quoted? false))))))

(defn- tokenize [text]
  (loop [start 0 tokens []]
    (if (= start (count text))
      tokens
      (let [value-at (.indexOf ^String text "{{" start)
            tag-at (.indexOf ^String text "{%" start)
            positions (filter #(not= -1 %) [value-at tag-at])]
        (if (empty? positions)
          (conj tokens [:text (subs text start)])
          (let [at (apply min positions) kind (if (= at value-at) :value :tag)
                end (closing-position text (+ at 2) (if (= kind :value) "}}" "%}"))]
            (recur (+ end 2)
                   (cond-> tokens (< start at) (conj [:text (subs text start at)])
                     true (conj [kind (str/trim (subs text (+ at 2) end))])))))))))

(declare parse-nodes)

(defn- parse-if [tokens index text depth]
  (let [predicate (compile-test (subs text 3))
        [yes stop at] (parse-nodes tokens (inc index) #{"else" "endif"} (inc depth))]
    (case stop
      "endif" [[:if predicate yes []] (inc at)]
      "else" (let [[no ending end] (parse-nodes tokens (inc at) #{"endif"} (inc depth))]
               (when-not (= "endif" ending) (reject! :invalid-template "An if block is not closed."))
               [[:if predicate yes no] (inc end)])
      (reject! :invalid-template "An if block is not closed."))))

(defn- parse-for [tokens index text depth]
  (let [[_ binding source] (re-matches #"(?s)for\s+([A-Za-z_][A-Za-z0-9_-]*)\s+in\s+(.+)" text)]
    (when-not (and binding (not= "forloop" binding))
      (reject! :invalid-template "For requires a variable name and an explicit collection path."))
    (let [read-items (compile-expression source)
          [children stop at] (parse-nodes tokens (inc index) #{"endfor"} (inc depth))]
      (when-not (= "endfor" stop) (reject! :invalid-template "A for block is not closed."))
      [[:for binding read-items children] (inc at)])))

(defn- parse-nodes [tokens start stops depth]
  (when (> depth 64) (reject! :invalid-template "Template blocks exceed the supported nesting depth."))
  (loop [index start nodes []]
    (if (= index (count tokens))
      [nodes nil index]
      (let [[kind text] (nth tokens index)]
        (cond
          (= kind :text) (recur (inc index) (conj nodes [:text text]))
          (= kind :value) (recur (inc index) (conj nodes [:value (compile-expression text)]))
          (contains? stops text) [nodes text index]
          (re-find #"^if\s+" text) (let [[node next-index] (parse-if tokens index text depth)] (recur next-index (conj nodes node)))
          (re-find #"^for\s+" text) (let [[node next-index] (parse-for tokens index text depth)] (recur next-index (conj nodes node)))
          :else (reject! :invalid-template "The template tag is unsupported or out of place."))))))

(defn compile-template
  "Compile inline text with dotted variables, if/else, for, and deterministic
  upper/lower/count/default filters. No includes, inheritance, or executable extensions."
  [text]
  (when-not (string? text) (reject! :invalid-template "A template must be a string."))
  (let [[nodes _ _] (parse-nodes (tokenize text) 0 #{} 0)] (InlineTemplate. nodes)))

(defn- append-value! [^StringBuilder output value]
  (cond
    (or (identical? absent value) (nil? value)) nil
    (inst? value) (.append output (str (Instant/ofEpochMilli (.getTime ^Date value))))
    (or (string? value) (number? value) (boolean? value) (char? value)
        (keyword? value) (symbol? value) (uuid? value)) (.append output (str value))
    :else (reject! :invalid-value "Template interpolation requires a scalar value.")))

(defn- render-nodes! [nodes input locals ^StringBuilder output]
  (doseq [[kind a b c] nodes]
    (case kind
      :text (.append output ^String a)
      :value (append-value! output (a input locals))
      :if (render-nodes! (if (a input locals) b c) input locals output)
      :for (let [items (b input locals)]
             (when-not (or (identical? absent items) (nil? items))
               (when-not (sequential? items)
                 (reject! :invalid-value "A template loop requires a sequential collection."))
               (let [length (count items)]
                 (reduce (fn [index item]
                           (render-nodes! c input
                                          (assoc locals a item "forloop" {:counter (inc index) :counter0 index
                                                                         :first (zero? index) :last (= (inc index) length)})
                                          output)
                           (inc index)) 0 items)))))))

(defn render-template
  "Render to plain text. Missing/nil variables render empty. Collections require
  explicit loops or a count filter. This does not perform HTML sanitization."
  [compiled input]
  (when-not (instance? InlineTemplate compiled)
    (reject! :invalid-template "Supply a compiled inline template."))
  (let [output (StringBuilder.)]
    (render-nodes! (.-nodes ^InlineTemplate compiled) input {} output)
    (.toString output)))
