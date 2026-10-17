# Siftell reference

## Public Clojure API

Require `[siftell.api :as s]`.

| Function | Result |
| --- | --- |
| `(s/compile definition)` | Reusable compiled plan |
| `(s/compile definition options)` | Plan with caller-owned schema options and template error policy |
| `(s/select plan input)` | Ordered `:fragment-ids`, `:rule-ids` and `:provenance` |
| `(s/render plan input)` | Ordered `:fragments`, `:diagnostics` and `:provenance` |
| `(s/describe plan)` | `:definition-id`, `:match` and condition/rule/fragment counts |
| `(s/read-definition text)` | One definition map from EDN text |
| `(s/read-definition text {:max-chars n})` | Reading with an explicit positive character limit |

Plans are intended for reuse. Compilation prepares the schema validator,
conditions, rule expressions and templates. Each `select` or `render` call
validates its full input once and uses a fresh condition-result cache.

Compilation options:

| Option | Default | Meaning |
| --- | --- | --- |
| `:schema-options` | `nil` | Options passed to Malli, including a caller-owned `:registry` |
| `:template-errors` | `:omit` | `:omit` records a diagnostic and omits a failing selected template; `:throw` throws |

Definitions are finite EDN data. Caller-supplied schema options may contain
trusted schema behavior. Authored function forms are never evaluated.
Compilation snapshots mutable `#inst` values, and returned fragments receive
independent copies of those values. Records, mutable numeric objects and
unreadable symbols are rejected. Clojure metadata is excluded from definition
behavior and identity. Treat compiled plans as opaque values.

## Definition

```clojure
{:schema [:map ...]
 :match :all
 :conditions {:condition-id condition-spec}
 :fragments {:fragment-id fragment-spec}
 :rules [{:id :rule-id :when expression :emit [:fragment-id]}]}
```

| Key | Default | Contract |
| --- | --- | --- |
| `:schema` | `:any` | Malli schema for the entire input |
| `:match` | `:all` | `:all` or `:first` |
| `:conditions` | `{}` | Named condition specifications |
| `:fragments` | `{}` | Named text or value fragments |
| `:rules` | Required | Ordered vector of rules |

Condition, fragment and rule IDs are keywords or nonblank strings. Rule IDs
must be unique. References must resolve to declared IDs. Unknown declaration
keys are rejected.

A fragment contains exactly one of `:template` or `:value`:

```clojure
{:template "Hello {{person.name}}."
 :slot :greeting
 :attributes {:channel :email}}

{:value {:action :open-inbox}
 :slot :action
 :attributes {:label "Open inbox"}}
```

`:template` is text. `:value` is arbitrary finite EDN data, including nil.
`:slot` is optional metadata. `:attributes` is an optional map. Both are copied
to the result when supplied.

## Conditions

A leaf condition reads one exact path:

```clojure
{:path [:customer :plan] :op :eq :value :premium}
```

Path components are keywords, strings or nonnegative integer indexes. Map keys
are exact: `:name` and `"name"` are different keys. Integer indexes address
sequential data. `[]` addresses the complete current input. A missing key,
out-of-range index or inaccessible intermediate value produces a missing
path.

| Operator | Operand | Meaning |
| --- | --- | --- |
| `:eq` / `:neq` | `:value` | Clojure `=` / `not=` |
| `:in` / `:not-in` | Sequential or set `:value` | Membership of the observed value |
| `:gt` / `:gte` / `:lt` / `:lte` | Finite numeric `:value` | Numeric ordering |
| `:between` | `:value [lower upper]` | Inclusive numeric interval; endpoints must be ascending |
| `:contains` | `:value` | Substring for strings; element equality for sequential/set data |
| `:present` | None | The path exists, including a present nil value |
| `:missing` | None | The path does not exist |
| `:nil?` | None | The path exists and its value is nil |
| `:some` / `:every` | `:where` condition | Quantification over sequential items |

Every comparison operator, including `:neq` and `:not-in`, returns false for
a missing path. A present nil value is ordinary data: `:eq nil` matches it;
numeric ordering against it raises `:invalid-value`. Input-schema validation
may reject nil before condition evaluation.

Equality and membership use Clojure's typed equality. For example, `1` and
`1.0` differ under `=`. Numeric ordering follows Clojure's comparison
compatibility without implicit conversion; incompatible numeric categories
raise `:invalid-value`. Substring matching is case-sensitive. `:contains` accepts strings, sequential data and
sets; maps are unsupported.

Presence distinguishes missing and nil:

| Input | `:present` | `:missing` | `:nil?` | `:eq` with `:value nil` |
| --- | --- | --- | --- | --- |
| `{}` | false | true | false | false |
| `{:field nil}` | true | false | true | true |
| `{:field false}` | true | false | false | false |

These examples inspect `[:field]`. Presence operators have no `:value` key.

Condition groups contain specifications:

```clojure
{:all [condition-spec ...]}
{:any [condition-spec ...]}
{:not condition-spec}
```

Groups short-circuit from left to right. Empty `:all` is true; empty `:any` is
false.

Quantifiers evaluate `:where` relative to each item:

```clojure
{:path [:items]
 :op :some
 :where {:path [:quantity] :op :gt :value 1}}
```

`:some` of an empty sequence is false; `:every` is true. A missing collection
path is false for both. A present nil, map, set or scalar raises
`:invalid-value` because quantifiers require sequential data.

## Rule expressions and ordering

`:when` accepts a named condition ID, a boolean, or a logical vector:

```clojure
:premium
true
[:all :premium :active]
[:any :premium [:not :paused]]
[:not :expired]
```

`:all` and `:any` accept zero or more operands; `:not` requires exactly one.
Omitted `:when` means true. Named conditions are evaluated when needed, at
most once per invocation. Boolean expressions short-circuit in operand order.

Rules are examined in vector order. `:match :all` retains every matching
rule. `:match :first` stops at the first matching rule, even when that rule
emits no fragments. `:emit` must be a vector of declared fragment IDs.

Fragment order follows matching rule order, then each rule's `:emit` order.
Duplicate fragment IDs are retained only at first emission. Slots do not
change selection or deduplication.

## Templates

Templates render the unmodified input. Nested paths use dots:

```text
Hello {{customer.name}}.
The first item is {{items.0.name}}.
```

Template names resolve keyword or string map keys. A step matching both
forms, such as `:name` and `"name"` in the same map, is ambiguous and causes a
render failure. Name segments start with an ASCII letter or underscore and
contain ASCII letters, digits, underscores or hyphens. Numeric segments are explicit
sequence indexes.

Missing or nil interpolation produces an empty string. Strings, numbers,
booleans, characters, keywords, symbols, UUIDs and instants can be interpolated;
collections cannot. Text is emitted verbatim.

Supported blocks:

```text
{% if urgent %}Action needed{% else %}For your information{% endif %}
{% if not urgent %}No immediate action needed{% endif %}
{% for item in items %}{{item.name}}{% endfor %}
```

`if` tests a path or filtered path, optionally preceded by `not`. Missing, nil, false, empty
strings and empty collections are false. Zero is true. Comparisons belong in
conditions. Blocks may be nested.

`for` iterates sequential collections in supplied order. Missing/nil
collections produce no iterations. Maps, sets and scalars cause a render
failure. Inside a loop:

| Variable | Meaning |
| --- | --- |
| `forloop.counter` | One-based iteration number |
| `forloop.counter0` | Zero-based iteration number |
| `forloop.first` | True on the first iteration |
| `forloop.last` | True on the last iteration |

Filters may be piped:

| Syntax | Behavior |
| --- | --- |
| `{{name\|upper}}` | Uppercase string; missing/nil becomes empty text |
| `{{name\|lower}}` | Lowercase string; missing/nil becomes empty text |
| `{{items\|count}}` | Count a string, map, set or sequence; missing/nil is zero |
| `{{name\|default:"Guest"}}` | Replace missing/nil with the quoted literal |
| `{{name\|default:"Guest"\|upper}}` | Apply filters left to right |

`default` preserves false, zero and empty strings. `upper`/`lower` require
strings; `count` rejects numeric and boolean scalars. Unsupported template
syntax fails compilation.

## Results and errors

`select` returns:

```clojure
{:fragment-ids [:subject :body]
 :rule-ids [:notify]
 :provenance {:definition-id "64 lowercase hexadecimal characters"}}
```

`render` returns:

```clojure
{:fragments [{:id :body :text "Hello." :slot :body :attributes {}}]
 :diagnostics []
 :provenance {:definition-id "64 lowercase hexadecimal characters"
              :rule-ids [:notify]}}
```

Value fragments contain `:value` in place of `:text`. Optional metadata appears
when supplied. The definition digest covers authored EDN. Caller-provided schema options
are separate configuration; changing an external registry implementation can
change behavior while the definition identity stays the same.

A selected template failure produces this diagnostic under the default
`:template-errors :omit` policy:

```clojure
{:severity :error
 :code :template-render-failed
 :fragment-id :body
 :message "A selected fragment could not be rendered"}
```

The fragment is omitted and remaining selected fragments are processed.
With `:template-errors :throw`, the call throws instead. Input validation,
condition evaluation and definition failures also throw `ExceptionInfo`.

The `:siftell/error` key classifies errors:

| Value | Meaning |
| --- | --- |
| `:invalid-definition` | Invalid declaration shape or reference |
| `:invalid-condition` | Invalid condition specification |
| `:invalid-template` | Invalid template syntax |
| `:invalid-input` | Full input fails its Malli schema |
| `:invalid-value` | An observed value cannot support the requested operation |
| `:template-render-failed` | A selected template failed under strict mode |
| `:invalid-plan` | A public operation received something other than a compiled plan |
| `:invalid-edn` | Text does not contain exactly one valid EDN definition map |
| `:definition-too-large` | Text exceeds the configured reader limit |

Error data provides relevant `:definition-path`, input `:path`, `:op`,
`:fragment-id` or input schema `:errors` metadata. Observed input values are excluded from these diagnostics.

`read-definition` defaults to `(* 4 1024 1024)` characters, measured with
Clojure `count`. It accepts standard `#uuid` and `#inst` literals and rejects
arbitrary tagged literals, empty input and trailing forms. It reads text only;
the caller supplies I/O explicitly.
