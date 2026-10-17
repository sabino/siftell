# Composing content with Siftell

Siftell separates three decisions: conditions inspect input data, rules select
fragment IDs, and fragments provide text or structured values. Compile the
definition when it changes. Reuse that plan for inputs that share its schema.

Run every example with:

```sh
clojure -Srepro -M:examples
```

Each example also runs in the test suite and compares actual output with
explicit expectations.

## Delivery notifications: compose several kinds of content

The [delivery example](../examples/siftell/examples/delivery.clj) selects a
subject, a message body and a structured tracking action:

```clojure
{:subject {:template "An update on delivery {{delivery.tracking-id}}"
           :slot :subject
           :attributes {:channel :email}}
 :body {:template "Hi {{recipient.name}}, your delivery is delayed by {{delivery.delay-hours}} hours."
        :slot :body
        :attributes {:tone :informative}}
 :action {:value {:action :view-tracking :label "Track delivery"}
          :slot :action
          :attributes {:channel :email}}}
```

The rule's `:emit [:subject :body :action]` establishes output order. Templates
become `:text`; a `:value` fragment carries its value through unchanged. Slots
and attributes are preserved for the consumer.

To explore interactively, include the examples directory in the REPL classpath:

```sh
clojure -Srepro -Sdeps '{:paths ["src" "examples"]}'
```

Then:

```clojure
(require '[siftell.examples.delivery :as delivery])

(:fragments (:result (delivery/run)))
;; => subject text, body text, then the tracking action
```

## Workflow messages: first-match precedence

The [workflow example](../examples/siftell/examples/workflow.clj) uses
`:match :first`:

```clojure
{:match :first
 :rules [{:id :blocked-first :when :blocked :emit [:blocked]}
         {:id :urgent-next :when :urgent :emit [:urgent]}
         {:id :otherwise :when true :emit [:queued]}]}
```

A request that is both blocked and urgent receives the blocked message.
The explicit final `true` rule provides a fallback. Rule order is therefore
part of the definition's behavior.

The fallback template demonstrates optional data:

```text
Request {{request.id}} is queued{% if request.owner %} for {{request.owner}}{% else %} for assignment{% endif %}.
```

Missing, nil, false, empty strings and empty collections are false in template
conditionals. Numeric zero is true.

## Eligibility explanations: collect all matching reasons

The [eligibility example](../examples/siftell/examples/eligibility.clj)
composes explanations for a community workshop. Named conditions can be
combined directly in a rule:

```clojure
[:all :old-enough [:any :volunteer :member]]
```

With `:match :all`, several rules can add reasons. When two rules emit the same
`:age` fragment, the result includes it once, at the position of its first
emission. Several independent reasons may share `:slot :reason`.

Use named conditions for reusable decisions. Inline condition groups are
useful when a named condition itself combines several observations:

```clojure
{:all [{:path [:applicant :age] :op :gte :value 16}
       {:any [{:path [:applicant :member] :op :eq :value true}
              {:path [:applicant :volunteer-hours] :op :gte :value 2}]}]}
```

These group children are condition specifications. Rule expression operands,
such as `:old-enough`, refer to named conditions.

## Nested collections: test items and retain the original input

The [collection example](../examples/siftell/examples/collections.clj) finds a
backordered item inside any order:

```clojure
{:path [:orders]
 :op :some
 :where {:path [:items]
         :op :some
         :where {:path [:backordered] :op :eq :value true}}}
```

Each `:where` path is relative to the current item. Nest quantifiers to inspect
another collection level. `:every` follows the same form. For an empty
sequence, `:some` is false and `:every` is true.

Matching a collection leaves it intact. The example's template loops over
every order and every item, including items that did not trigger the
condition. Its exact output is:

```text
A101: Notebook x1; B202: Pen x2, Marker x1 (backordered).
```

Use explicit indexes when you intend to inspect a particular position:

```clojure
{:path [:orders 1 :items 0 :quantity] :op :gt :value 0}
```

## Diagnostics: distinguish input failures from rendering failures

The [diagnostics example](../examples/siftell/examples/diagnostics.clj) selects
two templates:

```clojure
{:object {:template "{{customer}}"}
 :greeting {:template "Hello {{customer.name}}."}}
```

Interpolating the customer map as text fails. Interpolating its string name
succeeds. Selection still reports both IDs; rendering returns the greeting and
a diagnostic identifying `:object`:

```clojure
{:severity :error
 :code :template-render-failed
 :fragment-id :object
 :message "A selected fragment could not be rendered"}
```

With the example's `definition` and `input`, enable strict rendering at
compilation:

```clojure
(require '[siftell.api :as s]
         '[siftell.examples.diagnostics :as diagnostics])

(def plan (s/compile diagnostics/definition {:template-errors :throw}))
```

Definition errors and schema-invalid inputs throw structured errors. Inspect
the error data at the boundary where your application handles them:

```clojure
(try
  (s/render plan diagnostics/input)
  (catch clojure.lang.ExceptionInfo error
    (select-keys (ex-data error) [:siftell/error :path :errors :fragment-id])))
```

Error metadata omits observed input values. The application controls how it
presents or records a failure.

## Schema registries and plan reuse

The [registry example](../examples/siftell/examples/registries.clj) defines
reusable Malli types without changing a global registry:

```clojure
(require '[malli.core :as m]
         '[siftell.api :as s]
         '[siftell.examples.registries :as registries])

(def registry
  {:customer/name [:string {:min 1}]
   :customer/plan [:enum :standard :premium]
   :customer/profile [:map [:name [:ref :customer/name]]
                      [:plan [:ref :customer/plan]]]})

(def plan
  (s/compile registries/definition
    {:schema-options {:registry (merge (m/default-schemas) registry)}}))

(mapv #(s/render plan %) registries/inputs)
```

The definition uses `[:ref :customer/profile]` in its schema. The compiled plan
retains its validator and prepared conditions/templates. Each call keeps its
own condition cache and output; data from an earlier input is not reused.
Treat definition data and caller-provided schema behavior as immutable for the
plan's lifetime. Keep the plan while that configuration is current, compile a
new plan when it changes, and drop references when the plan is no longer
needed. Plans hold no open resources and require no close or disposal call.
Compilation detaches mutable EDN instants from the supplied definition;
returned value fragments detach them again. Plans themselves are opaque.

`select` returns IDs and matching rules without rendering templates. `render`
performs selection itself. Calling both operations performs two schema
validations and two selections.

`describe` returns counts, the matching policy and definition identity. The
identity hashes the authored definition. Caller-provided schema options are
separate configuration: changing an external registry implementation can
change behavior while the definition identity stays the same.

## Reading definitions as EDN

File or network access belongs to the caller:

```clojure
(def definition
  (s/read-definition (slurp "notification.edn")))

(def plan (s/compile definition))
```

`read-definition` accepts exactly one EDN map. It supports standard `#uuid` and
`#inst` literals. The default limit is 4 Mi characters, configurable with
`{:max-chars n}`. Compilation validates the declaration after reading it.
