(ns cw.result
  (:require [clojure.string :as str]))

;; §3.2 — single step / provider execution outcome.
(defrecord Result
           [provider        ; :claude | :gemini | :codex | :shell | <custom-key>
            model           ; string or nil
            text            ; string (final assistant message or shell stdout)
            ok?             ; boolean
            error           ; nil or {:type :exec|:timeout|:parse|:provider|:cost-cap|:fallback-exhausted|:gate-rejected
                   ;         :message "..." :details {...}}
            usage           ; nil or {:input-tokens N :output-tokens N :cached-input-tokens N}
            cost-usd        ; nil or double (nil = unknown, not zero)
            duration-ms     ; long
            session-id      ; nil or string (for resume)
            fallback-chain  ; vector describing providers attempted before success/failure
            raw])           ; original parsed output, for debugging

(defn result
  "Keyword constructor so call sites stay readable as the record grows."
  [{:keys [provider model text ok? error usage cost-usd duration-ms
           session-id fallback-chain raw]
    :or   {text "" fallback-chain [] duration-ms 0}}]
  (->Result provider model text (boolean ok?) error usage cost-usd
            duration-ms session-id fallback-chain raw))

(defn ok-result
  "A trivial success Result, used by meta commands that don't run a provider."
  ([] (ok-result ""))
  ([text] (result {:provider :cw :text text :ok? true})))

;; §3.3 — outcome of a chain (linear or DAG).
(defrecord ChainResult
           [ok?
            results            ; vector of Result, in execution order
            final-text         ; text of the terminal step (or join step in DAG)
            total-cost-usd
            total-duration-ms
            failed-at          ; nil or step-index/name
            error              ; nil or error map
            run-id             ; uuid string, used for audit log + replay
            plan])             ; the executed plan (for replay)

(defn chain-result
  [{:keys [ok? results final-text total-cost-usd total-duration-ms
           failed-at error run-id plan]
    :or   {results [] final-text ""}}]
  (->ChainResult (boolean ok?) results final-text total-cost-usd
                 total-duration-ms failed-at error run-id plan))

;; ── loop-harness RESULT: protocol (opt-in via --result-codes) ───────────────
(defn result-exit-code
  "Maps a finished result to a process exit code. Default (result-codes? falsey)
   is the legacy 0/1 on :ok?. When result-codes? is true AND the run succeeded,
   the terminal `RESULT:` sentinel in the text refines the code:
     RESULT: NOTHING_TO_DO → 0 · RESULT: BLOCKED … → 2 · RESULT: DONE …/none → 0.
   A failed run is always 1 regardless of the flag."
  [result result-codes?]
  (let [ok? (:ok? result)]
    (cond
      (not ok?) 1
      (not result-codes?) 0
      :else
      (let [text (or (:final-text result) (:text result) "")
            line (->> (str/split-lines (str text))
                      (map str/trim)
                      (filter #(str/starts-with? % "RESULT:"))
                      last)]
        (cond
          (nil? line) 0
          (re-find #"(?i)\bNOTHING_TO_DO\b" line) 0
          (re-find #"(?i)\bBLOCKED\b" line) 2
          :else 0)))))
