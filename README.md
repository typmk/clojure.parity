# Parity

Clojure compatibility testing and analysis toolkit. Measures how close an alternative Clojure implementation is to JVM Clojure — using the JVM itself as the spec.

## What it does

```
DISCOVER    langmap (reflection) + depgraph (source analysis)
              → What does Clojure need from the JVM?

GENERATE    specgen (reflection) + hand-written specs
              → Test expressions for every public var

REWRITE     portabilize
              → Mechanically rewrite JVM code to portable code

TEST        expand → capture → test
              → JVM is the oracle. Compare any impl against it.

ANALYZE     tree + coverage
              → What to implement next. What's been ported.
```

## Usage

Everything runs through the `par` CLI:

```bash
# Generate specs by reflecting on JVM namespaces
./par specgen

# Expand parametric templates into concrete test expressions
./par expand

# Capture reference results from JVM Clojure
./par capture

# Test an alternative implementation against the reference
./par test

# Full pipeline: specgen → expand → capture → stats
./par full

# Dependency analysis
./par deps src/clojure/core.clj    # source dependency graph
./par discover                      # JVM host contract via reflection
./par tree                          # merged implementation roadmap

# Rewrite JVM-specific source to portable Clojure
./par port src/clojure/core.clj

# Coverage analysis
./par coverage
```

## Components

| File | Purpose |
|------|---------|
| `parity.clj` | Test runner: expand, capture, test, stats |
| `specgen.clj` | Auto-generate test specs via JVM reflection |
| `depgraph.clj` | Source-level dependency graph (uses rewrite-clj) |
| `langmap.clj` | JVM host contract discovery via reflection |
| `tree.clj` | Merged dependency tree and implementation roadmap |
| `portabilize.clj` | Mechanical JVM → portable Clojure rewriter |
| `utils.clj` | Bracket checker, form printer |
| `spec/` | Hand-written test specs for core namespaces |
| `spec/gen/` | Auto-generated specs (~57 namespaces) |

## Requirements

- Clojure 1.12+
- JVM 21+

## License

Copyright (c) Apollo Nicolson and contributors.

Distributed under the Eclipse Public License 2.0.
