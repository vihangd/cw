(ns cw.verbosity
  "Stderr breadcrumb helpers. stdout is reserved for pasteable output;
   everything diagnostic goes here, gated by verbosity level.
     0 = silent  ·  1 (-v) = per-step breadcrumbs  ·  2 (-vv) = + intermediate text"
  (:require [clojure.string :as str]))

(defn eprintln [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

(defn at
  "Print msg to stderr only if current verbosity >= level."
  [verbosity level & msg]
  (when (>= (or verbosity 0) level)
    (apply eprintln msg)))

(defn step [verbosity label]
  (at verbosity 1 (str "→ " label)))

(defn detail [verbosity label text]
  (at verbosity 2 (str "  " label ":")
      (str/trim (str text))))

(defn warn [& msg]
  (apply eprintln (cons "⚠" msg)))

(defn cost-line
  "Cost summary to stderr. Shown when verbosity>=1 OR --cost was passed."
  [verbosity cost? cost-usd]
  (when (and cost-usd (or cost? (>= (or verbosity 0) 1)))
    (eprintln (format "cost: $%.4f" (double cost-usd)))))
