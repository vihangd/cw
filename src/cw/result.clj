(ns cw.result)

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
