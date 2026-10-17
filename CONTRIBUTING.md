# Contributing

Use Java 21 or newer, the Clojure CLI and Babashka.

## Run the checks

```sh
bb check
```

This runs the tests, executable examples, release build and package verification
in sequence. Individual tasks are available for focused work:

```sh
bb test
bb examples
bb build
bb check-package
bb version
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

The library JAR packages runtime source and required metadata. Tests and build
tooling remain outside that artifact; the documentation JAR includes the guide,
API reference and executable examples.

## Review

Explain the problem, resulting behavior and checks run. Include an exact
before/after example when a semantic change is involved. Update
[CHANGELOG.md](CHANGELOG.md) for user-visible changes.

Release tooling uses Babashka tasks and the maintained JReleaser GitHub Action.
See [Publishing](doc/publishing.md) for versioned release preparation.
