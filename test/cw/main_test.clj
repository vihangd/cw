(ns cw.main-test
  "Hermetic tests for the pure CLI arg-parsing layer — the trickiest glue in
   cw and the place the flag-position bug previously lived."
  (:require [clojure.test :refer [deftest is testing]]
            [cw.main :as main]))

(def extract-verbosity #'cw.main/extract-verbosity)
(def split-chain-args  #'cw.main/split-chain-args)
(def parse-global      #'cw.main/parse-global)
(def flag-map          #'cw.main/flag-map)
(def coerce-val        #'cw.main/coerce-val)
(def spec (assoc main/cli-spec :steps-file {}))

;; ── extract-verbosity ───────────────────────────────────────────────────────
(deftest verbosity-counting
  (is (= [0 ["a" "b"]]   (extract-verbosity ["a" "b"])))
  (is (= [1 ["a"]]       (extract-verbosity ["-v" "a"])))
  (is (= [2 ["a"]]       (extract-verbosity ["-vv" "a"])))
  (testing "additive across repeats, order preserved"
    (is (= [2 ["x" "y"]] (extract-verbosity ["x" "-v" "y" "-v"])))
    (is (= [3 ["x"]]     (extract-verbosity ["-vv" "x" "-v"])))))

;; ── coerce-val ──────────────────────────────────────────────────────────────
(deftest coerce
  (is (= 5    (coerce-val :long "5")))
  (is (= 1.5  (coerce-val :double "1.5")))
  (is (= "x"  (coerce-val nil "x")))
  (is (= "ab" (coerce-val :long "ab")) "bad number coerces to the raw string, no throw"))

;; ── flag-map ────────────────────────────────────────────────────────────────
(deftest flagmap
  (let [fm (flag-map spec)]
    (is (= :provider (:k (fm "--provider"))))
    (is (= :provider (:k (fm "-p")))   "alias resolves to same entry")
    (is (true?  (:bool? (fm "--json"))))
    (is (false? (:bool? (fm "--provider"))))
    (is (= :double (:coerce (fm "--max-cost-usd"))))))

;; ── split-chain-args (chain raw tail) ───────────────────────────────────────
(deftest split-chain-prompts-and-flags
  (is (= [{} ["P1" "P2"]] (split-chain-args ["P1" "P2"]))
      "bare tokens are step prompts")
  (testing "per-step -p/--provider stays a step token (parsed later)"
    (is (= [{} ["-p" "c" "P1"]] (split-chain-args ["-p" "c" "P1"]))))
  (testing "global bool flags extracted"
    (is (= [{:json true} ["P"]]      (split-chain-args ["--json" "P"])))
    (is (= [{:dry-run true} ["P"]]   (split-chain-args ["--dry-run" "P"])))
    (is (= [{:yes true} ["P"]]       (split-chain-args ["-y" "P"])) "-y → :yes")
    (is (= [{:yes true} ["P"]]       (split-chain-args ["--yes" "P"]))))
  (testing "global value flags consume their value"
    (is (= [{:steps-file "f.edn"} []] (split-chain-args ["--steps-file" "f.edn"])))
    (is (= [{:max-cost-usd "2"} ["P"]] (split-chain-args ["--max-cost-usd" "2" "P"])))
    (is (= [{:model "sonnet"} ["P"]]   (split-chain-args ["-m" "sonnet" "P"]))
        "-m must alias to :model (step-opts reads :model)")
    (is (= [{:model "x"} ["P"]]        (split-chain-args ["--model" "x" "P"])))))

;; ── parse-global (position-independent flags + chain hard-stop) ──────────────
(deftest parse-global-flag-position
  (testing "flag BEFORE subcommand"
    (is (= [{:json true} ["research" "x"] nil]
           (parse-global ["--json" "research" "x"] spec))))
  (testing "flags AFTER a non-chain subcommand are still parsed"
    (is (= [{:provider "g" :step "2"} ["replay" "id"] nil]
           (parse-global ["replay" "id" "--provider" "g" "--step" "2"] spec))))
  (testing "alias + bare prompt"
    (is (= [{:provider "g"} ["hello"] nil]
           (parse-global ["-p" "g" "hello"] spec))))
  (testing "--k=v inline form, coerced"
    (is (= [{:max-cost-usd 1.5} ["hi"] nil]
           (parse-global ["--max-cost-usd=1.5" "hi"] spec))))
  (testing "value flag coerced (:long)"
    (is (= [{:runs 3} ["eval" "wf"] nil]
           (parse-global ["--runs" "3" "eval" "wf"] spec))))
  (testing "unknown --flag falls through as a positional (Unix-style)"
    (is (= [{} ["--bogus" "hi"] nil]
           (parse-global ["--bogus" "hi"] spec))))
  (testing "bool flag does NOT consume the next token"
    (is (= [{:dry-run true} ["wf"] nil]
           (parse-global ["--dry-run" "wf"] spec)))))

(deftest parse-global-chain-hard-stop
  (testing "chain as first positional → everything after is raw chain-raw"
    (is (= [{:json true} ["chain"] ["-p" "c" "P1" "--json"]]
           (parse-global ["--json" "chain" "-p" "c" "P1" "--json"] spec))
        "global flag before chain parsed; -p AND post-chain --json stay raw"))
  (testing "\"chain\" only triggers when it is the FIRST positional"
    (is (= [{} ["x" "chain" "y"] nil]
           (parse-global ["x" "chain" "y"] spec))
        "a later 'chain' token is an ordinary positional, not the subcommand")))

;; ── end-to-end glue: chain? detection mirrors -main ──────────────────────────
(deftest chain-detection
  (let [[_ pos craw] (parse-global ["--json" "chain" "a" "b"] spec)]
    (is (= "chain" (first pos)))
    (is (= ["a" "b"] craw)))
  (let [[_ pos craw] (parse-global ["research" "topic"] spec)]
    (is (not= "chain" (first pos)))
    (is (nil? craw))))
