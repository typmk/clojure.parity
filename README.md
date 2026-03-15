# clojure.parity

Clojure cross-compiler parity toolkit. Measures how close an alternative Clojure implementation is to JVM Clojure — using the JVM itself as the oracle.

## The contract

Parity generates the questions. You provide the answers.

```
parity produces:
  expressions.edn  →  [{:expr "(+ 1 2)" :category :arithmetic :it "+ 1 2"} ...]
  reference.edn    →  [{:expr "(+ 1 2)" :result "3" :type "java.lang.Long"} ...]

you produce:
  results.edn      →  [{:expr "(+ 1 2)" :result "3"} ...]
                   or  [{:expr "(+ 1 2)" :error "ArityException: ..."} ...]
```

Your harness reads `expressions.edn`, evaluates each `:expr` in your runtime, and writes `results.edn`. How you eval is your problem — JVM, native binary, transpiled JS, whatever. Parity compares and reports.

## Quick start

```bash
# 1. Setup (once): reflect on JVM, generate specs, capture reference
par init

# 2. Ship expressions.edn to your target, eval each :expr, write results.edn

# 3. Compare
par test results.edn
```

## Commands

```
SETUP
  par init                       Reflect → generate → capture reference

TEST
  par test                       Self-check (reference vs reference)
  par test <results.edn>         Compare target impl against reference
  par status                     Coverage dashboard

DISCOVER
  par reflect                    JVM host contract (what to implement)
  par reflect --edn              Machine-readable output
  par deps <src>                 Source dependency graph

ANALYZE
  par roadmap <src>              What to implement next (prioritized)
  par coverage <ported> <src>    What's been ported vs what remains

REWRITE
  par port <in.clj> [out.cljc]  JVM → portable Clojure (experimental)
```

## Writing a harness

A harness is ~20 lines in any language. Read EDN, eval, write EDN.

Example (Clojure — your target impl):

```clojure
(let [exprs (edn/read-string (slurp "expressions.edn"))
      results (mapv (fn [{:keys [expr]}]
                      (try
                        {:expr expr :result (pr-str (eval (read-string expr)))}
                        (catch Exception e
                          {:expr expr :error (str (class e) ": " (.getMessage e))})))
                    exprs)]
  (spit "results.edn" (pr-str results)))
```

Example (shell — any runtime with a REPL):

```bash
while IFS= read -r expr; do
  result=$(echo "$expr" | your-clojure-repl 2>&1)
  echo "{:expr \"$expr\" :result \"$result\"}"
done < <(clojure -e '(doseq [e (edn/read-string (slurp "expressions.edn"))] (println (:expr e)))')
```

## Layout

```
par                     CLI entry point (bash → core.clj)
deps.edn                Clojure project deps
src/parity/
  core.clj              Entry point, all commands as top-level functions
  specgen.clj           JVM reflection → test specs (.edn)
  parity.clj            Expand, capture, test, status
  depgraph.clj          Source dependency graph
  langmap.clj           JVM host contract discovery
  tree.clj              Merged dependency tree + roadmap
  portabilize.clj       JVM → portable rewriter (experimental)
  gen_parity.clj        .cljc test suite generator (alternative format)
  utils.clj             Bracket checker, form printer
  color.clj             ANSI terminal helpers
lang/                   Generated: Clojure runtime specs (gitignored)
contrib/                Generated: contrib library specs (gitignored)
results/                Generated: expressions.edn, reference.edn (gitignored)
```

## Requirements

- Clojure 1.12+
- JVM 21+

## License

Copyright (c) Apollo Nicolson and contributors.

Distributed under the Eclipse Public License 2.0.
