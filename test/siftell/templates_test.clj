(ns siftell.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [siftell.templates :as t]))

(defn- render [text input] (t/render-template (t/compile-template text) input))
(defn- error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest text-variables-and-explicit-indexes
  (is (= "" (render "" {})))
  (is (= "Hello Avery!" (render "Hello {{ customer.name }}!" {:customer {:name "Avery"}})))
  (is (= "Hello Avery!" (render "Hello {{ customer.name }}!" {"customer" {"name" "Avery"}})))
  (is (= "second" (render "{{items.1.name}}" {:items [{:name "first"} {:name "second"}]})))
  (is (= "" (render "{{items.name}}" {:items [{:name "first"}]})))
  (is (= "[] [] [false] [0]" (render "[{{missing}}] [{{x}}] [{{flag}}] [{{zero}}]" {:x nil :flag false :zero 0})))
  (is (= "<b>plain</b>" (render "{{value}}" {:value "<b>plain</b>"})))
  (is (= "1/3" (render "{{value}}" {:value 1/3})))
  (is (= "a" (render "{{value}}" {:value \a})))
  (is (= "1970-01-01T00:00:00Z" (render "{{value}}" {:value (java.util.Date. 0)})))
  (is (= :invalid-value (:siftell/error (error-data #(render "{{x}}" {:x "one" "x" "two"}))))))

(deftest branches-have-explicit-truth-semantics
  (let [text "{% if flag %}yes{% else %}no{% endif %}"]
    (doseq [value [nil false "" [] '() #{} {}]] (is (= "no" (render text {:flag value}))))
    (doseq [value [true 0 "false" [nil] {:x false}]] (is (= "yes" (render text {:flag value}))))
    (is (= "no" (render text {}))))
  (is (= "missing" (render "{% if not absent %}missing{% endif %}" {})))
  (is (= "inner" (render "{% if outer %}{% if inner %}inner{% else %}other{% endif %}{% else %}none{% endif %}"
                         {:outer true :inner true})))
  (is (= "ok" (render "{% if\nflag %}ok{% endif %}" {:flag true}))))

(deftest loops-retain-order-and-lexical-scope
  (let [text "{% for item in items %}{{forloop.counter0}}:{{item.name}}{% if not forloop.last %},{% endif %}{% endfor %}"]
    (is (= "0:first,1:second" (render text {:items [{:name "first"} {:name "second"}]})))
    (is (= "" (render text {})))
    (is (= "" (render text {:items nil})))
    (is (= "" (render text {:items []}))))
  (is (= "a:1;a:2;b:3;root"
         (render "{% for group in groups %}{% for item in group.items %}{{group.name}}:{{item}};{% endfor %}{% endfor %}{{item}}"
                 {:groups [{:name "a" :items [1 2]} {:name "b" :items '(3)}] :item "root"})))
  (is (= "true,false;false,true;"
         (render "{% for item in items %}{{forloop.first}},{{forloop.last}};{% endfor %}" {:items [1 2]})))
  (doseq [value [false 0 "abc" #{} {}]]
    (is (= :invalid-value (:siftell/error (error-data #(render "{% for x in items %}{{x}}{% endfor %}" {:items value})))))))

(deftest safe-filter-pipelines-and-literal-delimiters
  (is (= "AVERY" (render "{{name|upper}}" {:name "Avery"})))
  (is (= "avery" (render "{{name|lower}}" {:name "AVERY"})))
  (is (= "GUEST" (render "{{name|default:\"Guest\"|upper}}" {})))
  (is (= "a|b:}}" (render "{{name|default:\"a|b:}}\"}}" {})))
  (is (= "line\nnext" (render "{{name|default:\"line\\nnext\"}}" {})))
  (is (= "false/0/" (render "{{a|default:\"x\"}}/{{b|default:\"x\"}}/{{c|default:\"x\"}}" {:a false :b 0 :c ""})))
  (is (= "false" (render "{{x|default:false}}" {})))
  (is (= "42" (render "{{x|default:42}}" {})))
  (is (= "%}" (render "{% if x|default:\"%}\" %}{{x|default:\"%}\"}}{% endif %}" {})))
  (doseq [[value expected] [[nil "0"] ["abc" "3"] [[1 2] "2"] [#{1 2} "2"] [{:a 1} "1"]]]
    (is (= expected (render "{{value|count}}" {:value value}))))
  (is (= "0" (render "{{value|count}}" {})))
  (is (= :invalid-value (:siftell/error (error-data #(render "{{age|upper}}" {:age 42})))))
  (is (= :invalid-value (:siftell/error (error-data #(render "{{flag|count}}" {:flag false}))))))

(deftest locale-independent-case-conversion
  (let [previous (java.util.Locale/getDefault)]
    (try
      (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr-TR"))
      (is (= "I/i" (render "{{small|upper}}/{{large|lower}}" {:small "i" :large "I"})))
      (finally (java.util.Locale/setDefault previous)))))

(deftest invalid-template-syntax-is-rejected-during-compilation
  (doseq [text [nil 42 "{{}}" "{{a" "{% if a %}x" "{% for a in xs %}x" "{% else %}" "{% endif %}"
                "{% if a %}x{% else %}y{% else %}z{% endif %}" "{% if a == b %}x{% endif %}"
                "{% for forloop in xs %}x{% endfor %}" "{% for item xs %}x{% endfor %}"
                "{{items[-1]}}" "{{items.-1}}" "{{items.999999999999999999999999}}"
                "{{x|default}}" "{{x|default:identity}}" "{{x|default:#=(bad)}}" "{{x|upper:1}}"
                "{{x|default:\"unterminated}}" "{{x|unknown}}"]]
    (is (= :invalid-template (:siftell/error (error-data #(t/compile-template text)))) (pr-str text))))

(deftest deeply-nested-blocks-fail-with-a-structured-error
  (let [text (str (apply str (repeat 65 "{% if flag %}")) "text"
                  (apply str (repeat 65 "{% endif %}")))]
    (is (= :invalid-template (:siftell/error (error-data #(t/compile-template text)))))))

(deftest deterministic-language-has-no-resource-or-executable-extension-path
  (let [unexpected (fn [& _] (throw (ex-info "Unexpected external access" {})))]
    (with-redefs [clojure.core/slurp unexpected io/resource unexpected io/reader unexpected]
      (is (= "ok" (render "{{value}}" {:value "ok"})))
      (doseq [text ["{% include \"private.txt\" %}" "{% extends \"base.html\" %}"
                    "{% now \"yyyy\" %}" "{% cycle a b %}" "{{items|random}}" "{{x|date}}"
                    "{% custom %}" "{{x|safe}}"]]
        (is (= :invalid-template (:siftell/error (error-data #(t/compile-template text)))))))))

(deftest reusable-plans-own-no-request-state-and-render-errors-do-not-dump-input
  (let [plan (t/compile-template "{% for item in items %}{{item.name}}{% endfor %}")
        pending (mapv (fn [n] (future (t/render-template plan {:items [{:name (str n)}]}))) (range 20))]
    (is (= (mapv str (range 20)) (mapv deref pending)))
    (is (= "" (t/render-template plan {})))
    (with-redefs [t/compile-template (fn [& _] (throw (ex-info "Unexpected recompilation" {})))
                  clojure.edn/read-string (fn [& _] (throw (ex-info "Unexpected parsing" {})))]
      (is (= "later" (t/render-template plan {:items [{:name "later"}]})))))
  (let [failure (error-data #(render "{{customer}}" {:customer {:secret "PRIVATE-VALUE"}}))]
    (is (= :invalid-value (:siftell/error failure)))
    (is (not (.contains (pr-str failure) "PRIVATE-VALUE"))))
  (is (= :invalid-template (:siftell/error (error-data #(t/render-template {} {}))))))
