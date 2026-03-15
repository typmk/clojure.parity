# Parity

Clojure parity testing toolkit. Measures how close an alternative Clojure implementation is to JVM Clojure — using the JVM itself as the oracle.

## How it works

1. **Reflect** on live JVM namespaces to discover every public var, its arglists, and metadata
2. **Generate** test expressions — parametric cross-products, edge cases, host interop, scaling
3. **Capture** reference results by evaluating every expression on JVM Clojure
4. **Test** an alternative implementation against the captured reference

No hand-written expected values. The JVM is the spec.

## Quick start

```bash
# Generate all specs (lang/ and contrib/)
./par full

# Or step by step:
./par specgen                      # generate spec files
./par expand                       # expand parametric templates → expressions.edn
./par capture                      # eval on JVM → reference.edn
./par test                         # compare target impl against reference
```

## Other tools

```bash
./par discover                     # JVM host contract (reflection)
./par deps <clojure-src>           # source dependency graph
./par tree <clojure-src>           # merged dependency + implementation roadmap
./par coverage <ported-dir> <src>  # host coverage analysis
./par port <in.clj> [out.cljc]    # rewrite JVM → portable Clojure
./par check <file...>              # bracket balance
```

## Layout

```
par                     CLI entry point (bash)
deps.edn                Clojure project deps
src/parity/             All Clojure source
  specgen.clj           Spec generator (JVM reflection → .edn)
  parity.clj            Test runner: expand, capture, test, stats
  depgraph.clj          Source-level dependency graph
  langmap.clj           JVM host contract discovery
  tree.clj              Merged dependency tree + roadmap
  portabilize.clj       JVM → portable rewriter
  utils.clj             Bracket checker, form printer
  color.clj             ANSI terminal helpers
lang/                   Generated specs: Clojure runtime (shipped namespaces)
contrib/                Generated specs: contrib libraries
```

## Spec format

Specs are `.edn` files with two forms:

- **Explicit tests**: `{:it "name" :eval "(expr)"}`
- **Parametric tests**: `{:describe "fn" :params {:x [...]} :template "(fn %x)"}`

Parametric tests expand to the cross-product of all param axes. Expected values come from JVM capture, not from the spec.

## Requirements

- Clojure 1.12+
- JVM 21+

## License

Copyright (c) Apollo Nicolson and contributors.

Distributed under the Eclipse Public License 2.0.
