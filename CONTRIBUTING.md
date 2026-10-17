# Contributing

Use Java 21 or newer and the Clojure CLI.

## Run the checks

```sh
clojure -Srepro -M:test
clojure -Srepro -M:examples
clojure -Srepro -T:build jar
python3 scripts/check-package.py
```

The examples are executable documentation. A behavior change should update
its focused tests, the affected example and the relevant reference section.

## Keep changes focused

Prefer data-oriented APIs and caller-owned configuration. Definitions should
remain declarative EDN. Compile reusable work once; keep input values,
condition-result caches and diagnostics local to each call.

Selection order, missing-versus-nil behavior, typed comparison semantics and
template errors are public contracts. Add a regression for a concrete behavior
change. Keep expectations independent of private implementation structures.

Document public options and structured errors. Diagnostics should identify
the failed declaration or fragment without recording observed input values.
Examples should use small self-contained data and assert their actual output.

The JAR packages library source and required metadata. Tests, examples and
development tooling remain outside the runtime artifact.

## Review

Explain the problem, resulting behavior and checks run. Include an exact
before/after example when a semantic change is involved. Update
[CHANGELOG.md](CHANGELOG.md) for user-visible changes.
