# clojure.parity

Parity testing for Clojure compilers and runtimes.

## How it works

1. Inspects every public function in JVM Clojure — arglists, type hints — and generates tests automatically.
2. Runs them on the JVM and records every answer.
3. Run `parity.cljc` on your implementation — a self-contained test against clojure.core.
4. `par test` compares your answers across all namespaces — what passes, what's missing, what to build next.

`par status` can analyse the Clojure dependency graph and host contract to prioritize what to build.
`par port` rewrites JVM Clojure source to portable Clojure.

## Quick start

```bash
par init --lang          # generate tests + JVM reference (~2s)
your-clojure parity.cljc # run against your implementation
```

## Detailed comparison

Write a program that evals each expression and writes `results.edn`:

```clojure
(let [exprs (edn/read-string (slurp "results/expressions.edn"))
      results (mapv (fn [{:keys [expr]}]
                      (try {:expr expr :result (pr-str (eval (read-string expr)))}
                        (catch Exception e
                          {:expr expr :error (str (class e) ": " (.getMessage e))})))
                    exprs)]
  (spit "results.edn" (pr-str results)))
```

```bash
par test results.edn
par status
```

## Options

```
par init --quick         ~2k tests
par init --balanced      ~9k tests (default)
par init --thorough      ~40k tests
par init --lang          shipped Clojure only (no contrib)
par init --contrib       contrib libraries only

par status --roadmap <clojure-src>    dependency graph + build order
par status --reflect                  host contract (interfaces, methods, types)

par port <in.clj> [out.cljc]         rewrite JVM interop to portable calls
par clear                             start over
```

## Requirements

- Clojure 1.12+
- JVM 21+

## License

Copyright (c) Apollo Nicolson and contributors. EPL-2.0.
