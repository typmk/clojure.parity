# clojure.parity

Test how close your Clojure implementation is to the real thing.

## How it works

1. Parity asks the JVM thousands of questions: `(+ 1 2)`, `(map inc [1 2 3])`, etc.
2. It records every answer: `3`, `(2 3 4)`, etc.
3. You ask your implementation the same questions.
4. Parity compares.

## Setup

```bash
par init
```

This generates `expressions.edn` (the questions) and `reference.edn` (the JVM's answers).

## Test your implementation

Write a small program that reads `expressions.edn`, evaluates each `:expr`, and writes `results.edn`:

```clojure
(let [exprs (edn/read-string (slurp "expressions.edn"))
      results (mapv (fn [{:keys [expr]}]
                      (try {:expr expr :result (pr-str (eval (read-string expr)))}
                        (catch Exception e
                          {:expr expr :error (str (class e) ": " (.getMessage e))})))
                    exprs)]
  (spit "results.edn" (pr-str results)))
```

Then compare:

```bash
par test results.edn
```

## See where you stand

```bash
par status
```

```
Pass:    893/1489 (60.0%)
Fail:    130
Missing: 373

Next wins:
  clojure.core    128 remaining
  clojure.string   26 remaining
```

## Options

```
par init --quick         ~2k tests, 2 seconds
par init --balanced      ~9k tests, 5 seconds (default)
par init --thorough      ~40k tests, 25 seconds
par init --lang          shipped Clojure only (no contrib)
par init --contrib       contrib libraries only

par status --roadmap <clojure-src>    what to implement next
par status --reflect                  what the JVM provides

par port <in.clj> [out.cljc]         rewrite JVM code to portable code
par clear                             start over
```

## Requirements

- Clojure 1.12+
- JVM 21+

## License

Copyright (c) Apollo Nicolson and contributors. EPL-2.0.
