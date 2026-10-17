# Siftell

[![Check](https://github.com/sabino/siftell/actions/workflows/check.yml/badge.svg)](https://github.com/sabino/siftell/actions/workflows/check.yml)

**Conditions sift the content. Templates tell the result.**

Siftell is a Clojure library for selecting and composing content from typed
data. Define conditions, fragments and ordered rules as EDN. Compile once,
then select fragment IDs or render content for many inputs.

Use it for delivery notifications, workflow messages, eligibility
explanations and other content assembled from explicit rules. Fragments can
contain text templates or structured values. Slots and attributes carry
your own metadata.

## Try it

Use Java 21 or newer and the Clojure CLI. From this checkout:

```sh
clojure -Srepro -M:examples
clojure -Srepro -M:test
```

Add Siftell to `deps.edn`:

```clojure
{:deps {io.github.sabino/siftell {:mvn/version "0.1.0"}}}
```

With Leiningen:

```clojure
[io.github.sabino/siftell "0.1.0"]
```

For local development, use `{:local/root "../siftell"}` in place of the
version map. Maven coordinates are `io.github.sabino:siftell:0.1.0`.
[Release artifacts](https://github.com/sabino/siftell/releases) include the
library, source and documentation JARs.

The runtime dependencies are Clojure and Malli.

## A complete example

```clojure
(require '[siftell.api :as s])

(def definition
  {:schema [:map
            [:recipient [:map [:name :string]]]
            [:delivery [:map [:status :keyword] [:delay-hours :int]]]]
   :conditions
   {:delayed {:path [:delivery :status] :op :eq :value :delayed}
    :long-delay {:path [:delivery :delay-hours] :op :gte :value 4}}
   :fragments
   {:notice
    {:template "Hi {{recipient.name}}, your delivery is delayed by {{delivery.delay-hours}} hours."
     :slot :body
     :attributes {:channel :email}}}
   :rules
   [{:id :delay-notice
     :when [:all :delayed :long-delay]
     :emit [:notice]}]})

(def plan (s/compile definition))

(def input
  {:recipient {:name "Mara"}
   :delivery {:status :delayed :delay-hours 6}})

(s/select plan input)
;; {:fragment-ids [:notice]
;;  :rule-ids [:delay-notice]
;;  :provenance {:definition-id "..."}}

(:fragments (s/render plan input))
;; [{:id :notice
;;   :text "Hi Mara, your delivery is delayed by 6 hours."
;;   :slot :body
;;   :attributes {:channel :email}}]

(s/describe plan)
;; {:definition-id "..." :match :all
;;  :conditions 2 :rules 1 :fragments 1}
```

`render` also returns `:diagnostics` and `:provenance`. Definition identity is a
SHA-256 digest of the authored EDN.

## Predictable selection

Rules run in vector order. The default `:match :all` selects every matching
rule. Set `:match :first` to select the first matching rule globally. A rule
emits fragments in its `:emit` vector order. Repeated fragment IDs appear once,
at their first emission.

A slot is metadata: several fragments can share a slot and all remain in the
result. Your application decides how slots and attributes affect delivery or
presentation.

Input is validated against the complete Malli schema on each `select` or
`render` call. Named conditions are evaluated lazily, at most once per call.
Conditions can inspect nested maps and collections. Templates receive the
original input, including every collection element.

## Templates and diagnostics

Templates support interpolation, `if`/`else`, nested `for` loops and a small
set of filters:

```clojure
{:template "Hello {{customer.name|upper}}."}
{:template "{% if urgent %}Action needed{% else %}For your information{% endif %}"}
{:template "{% for item in items %}{{item.name}}{% if not forloop.last %}, {% endif %}{% endfor %}"}
```

A selected template that fails at render time is omitted with a structured
diagnostic; other selected fragments continue rendering. Compile with
`{:template-errors :throw}` to make that failure throw instead. Invalid
definitions and schema-invalid inputs throw `ExceptionInfo` with
`:siftell/error` data.

## Learn through executable examples

`clojure -Srepro -M:examples` runs and checks all six scenarios:

| Example | Demonstrates |
| --- | --- |
| [Delivery notifications](examples/siftell/examples/delivery.clj) | Text and structured fragments, slots and attributes |
| [Workflow messages](examples/siftell/examples/workflow.clj) | First-match precedence, fallback and conditional text |
| [Eligibility explanations](examples/siftell/examples/eligibility.clj) | All matching reasons, logical expressions and deduplication |
| [Nested collections](examples/siftell/examples/collections.clj) | Quantifiers, nested loops and preserved input |
| [Diagnostics](examples/siftell/examples/diagnostics.clj) | Definition errors, input validation and template failures |
| [Schema registries and reuse](examples/siftell/examples/registries.clj) | Caller-owned Malli types and one plan for many inputs |

The [guide](doc/guide.md) explains composition patterns. The
[reference](doc/reference.md) specifies the definition format, operators,
template syntax and errors.

## Build

```sh
clojure -Srepro -T:build jar
python3 scripts/check-package.py
```

The build creates a thin source JAR with generated Maven metadata and
LICENSE. Verification loads that JAR from a temporary consumer and
checks rendering using its declared dependencies.

[Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [Publishing](doc/publishing.md) · [MIT license](LICENSE)
