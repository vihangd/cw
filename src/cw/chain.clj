(ns cw.chain
  "Unified linear + DAG chain runner with template substitution, cost cap,
   and stop-on-first-failure."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.process :as p]
            [cw.config :as config]
            [cw.provider :as provider]
            [cw.result :as r]
            [cw.verbosity :as v]))

;; ── template substitution ───────────────────────────────────────────────────
(defn substitute
  "Replaces {{stdin}} {{prev}} {{arg-N}} {{step-NAME}} in a template string.
   ctx = {:stdin s :prev s :args [..] :step-outputs {:id text}}."
  [tpl {:keys [stdin prev args step-outputs]}]
  (when tpl
    (-> (str tpl)
        (str/replace "{{stdin}}" (str stdin))
        (str/replace "{{prev}}" (str (or prev stdin)))
        (str/replace #"\{\{arg-(\d+)\}\}"
                     (fn [[_ n]]
                       (str (nth (vec args) (dec (Long/parseLong n)) ""))))
        (str/replace #"\{\{step-([A-Za-z0-9_-]+)\}\}"
                     (fn [[_ id]]
                       (str (get step-outputs (keyword id) "")))))))

;; ── single steps ────────────────────────────────────────────────────────────
(defn- run-shell-step [step ctx]
  (let [cmd (mapv #(substitute % ctx) (:cmd step))
        t0  (System/currentTimeMillis)
        {:keys [out err exit]}
        @(p/process cmd {:out :string :err :string})
        dur (- (System/currentTimeMillis) t0)]
    (if (zero? exit)
      (r/result {:provider :shell :text (str/trim out) :ok? true
                 :duration-ms dur :raw {:cmd cmd}})
      (r/result {:provider :shell :ok? false :duration-ms dur
                 :error {:type :exec
                         :message (str "shell exit " exit ": "
                                       (str/trim (str err)))}
                 :raw {:cmd cmd}}))))

(defn- run-llm-step [config step ctx]
  (let [prompt (substitute (:prompt step) ctx)]
    (provider/run config (assoc step :prompt prompt))))

;; ── gate steps (human approval checkpoint) ──────────────────────────────────
(defn gate-step? [step] (contains? step :gate))

(defn gate-decision
  "Pure: a raw answer line → :approved | :rejected. Default (blank/EOF/N) is
   reject — gates fail closed."
  [answer]
  (if (contains? #{"y" "yes"} (-> (str answer) str/trim str/lower-case))
    :approved :rejected))

;; Serializes concurrent gate prompts (a DAG wave with >1 :gate would otherwise
;; interleave writes/reads on /dev/tty and corrupt the interactive session).
(def ^:private tty-lock (Object.))

(defn- ask-tty
  "Prompts on the controlling terminal and reads one line FROM IT (not stdin —
   stdin is the chain's piped data). Throws when there is no controlling TTY;
   the caller turns that into a fail-closed rejection."
  [prompt]
  (locking tty-lock
    (with-open [w (io/writer "/dev/tty")]
      (.write w (str "\n" prompt " [y/N] "))
      (.flush w))
    (with-open [r (io/reader "/dev/tty")]
      (.readLine ^java.io.BufferedReader r))))

(defn- run-gate-step
  "A gate is a step that pauses for human approval. --yes (ctx :yes) bypasses
   it. No TTY and no --yes ⇒ fail closed (rejected) so headless/piped runs
   never hang. Rejection returns an :ok? false Result, so the existing
   stop-on-first-failure path aborts the chain."
  [step ctx]
  (let [g      (:gate step)
        prompt (if (string? g) (substitute g ctx) "Continue?")]
    (if (:yes ctx)
      (r/result {:provider :gate :ok? true :text "approved (--yes)"
                 :raw {:gate prompt :decision :auto}})
      (let [decision (try (gate-decision (ask-tty prompt))
                          (catch Throwable _ :no-tty))]
        (case decision
          :approved (r/result {:provider :gate :ok? true :text "approved"
                               :raw {:gate prompt :decision :approved}})
          :rejected (r/result {:provider :gate :ok? false
                               :error {:type :gate-rejected
                                       :message (str "gate declined: " prompt)}
                               :raw {:gate prompt :decision :rejected}})
          :no-tty   (r/result {:provider :gate :ok? false
                               :error {:type :gate-rejected
                                       :message (str "gate needs confirmation "
                                                     "but no TTY and no --yes: "
                                                     prompt)}
                               :raw {:gate prompt :decision :no-tty}}))))))

(defn- run-step
  "Dispatches a gate, shell, or LLM step. Never throws: any unexpected error
   (missing binary, parser blow-up, bad provider key) becomes a failed
   Result so the chain stops cleanly instead of aborting the process."
  [config step ctx]
  (try
    (cond
      (gate-step? step) (run-gate-step step ctx)
      (:cmd step)       (run-shell-step step ctx)
      :else             (run-llm-step config step ctx))
    (catch Throwable e
      (r/result {:provider (or (:provider step) :shell) :ok? false
                 :error {:type :exec
                         :message (str "step failed: "
                                       (or (.getMessage e) (class e)))}}))))

(defn parse-chain-steps
  "Ad-hoc chain tokens (`-p c P1 -p g P2 P3`) → vector of LLM step maps.
   `step-opts` (e.g. {:model .. :no-fallback true :timeout-ms ..}) is merged
   into every step so global CLI flags reach each step."
  [config tokens default-provider step-opts]
  (loop [[t & more] tokens
         cur default-provider
         steps []]
    (cond
      (nil? t) steps
      (#{"-p" "--provider"} t)
      (recur (rest more)
             (:key (config/resolve-provider config (first more))) steps)
      :else
      (recur more cur
             (conj steps (merge {:provider cur :prompt t} step-opts))))))

;; ── topology ────────────────────────────────────────────────────────────────
(defn- linear? [steps]
  (not-any? #(contains? % :depends-on) steps))

(defn- with-ids
  "Ensures every step has an :id (synthesizes :step-N for unnamed steps)."
  [steps]
  (map-indexed (fn [i s] (update s :id #(or % (keyword (str "step-" (inc i))))))
               steps))

(defn- topological-waves
  "Groups DAG steps into waves; each wave's deps resolved by earlier waves.
   Throws ex-info on cycle / unresolvable dependency."
  [steps]
  (let [steps (vec (with-ids steps))
        deps  (into {} (map (juxt :id #(set (:depends-on %)))) steps)]
    (loop [remaining steps, resolved #{}, waves []]
      (if (empty? remaining)
        waves
        (let [ready (filter #(every? resolved (deps (:id %))) remaining)]
          (when (empty? ready)
            (throw (ex-info "Cyclic or unresolvable :depends-on"
                            {:remaining (map :id remaining)})))
          (recur (remove (set ready) remaining)
                 (into resolved (map :id ready))
                 (conj waves (vec ready))))))))

;; ── runner ──────────────────────────────────────────────────────────────────
(defn- step-cost [res] (or (:cost-usd res) 0.0))

(defn- over-cap? [max-cost acc]
  (and max-cost (> acc max-cost)))

(defn- finalize [{:keys [run-id plan results ok? final-text failed-at error]}]
  (r/chain-result
   {:ok? ok? :results results :final-text final-text
    :total-cost-usd (reduce + 0.0 (map step-cost results))
    :total-duration-ms (reduce + 0 (map (comp long #(or % 0) :duration-ms) results))
    :failed-at failed-at :error error :run-id run-id :plan plan}))

(defn- run-chain-linear
  [config steps {:keys [stdin args max-cost-usd verbosity run-id
                        seed-outputs seed-prev yes]}]
  (loop [[step & more] steps, idx 0, prev seed-prev,
         outs (or seed-outputs {}), acc 0.0, results []]
    (if (nil? step)
      (finalize {:run-id run-id :plan {:steps steps} :results results :ok? true
                 :final-text (:text (last results))})
      (let [_   (v/step verbosity (str "step " (inc idx) " "
                                       (or (:id step) (:provider step) "shell")))
            ctx {:stdin stdin :prev prev :args args :step-outputs outs :yes yes}
            res (run-step config step ctx)
            acc (+ acc (step-cost res))]
        (v/detail verbosity (str "step " (inc idx)) (:text res))
        (cond
          (not (:ok? res))
          (finalize {:run-id run-id :plan {:steps steps}
                     :results (conj results res) :ok? false
                     :failed-at (or (:id step) idx) :error (:error res)})
          (over-cap? max-cost-usd acc)
          (finalize {:run-id run-id :plan {:steps steps}
                     :results (conj results res) :ok? false
                     :failed-at (or (:id step) idx)
                     :error {:type :cost-cap
                             :message (format "cost cap $%.4f exceeded ($%.4f)"
                                              (double max-cost-usd) acc)}})
          :else
          ;; A gate is a checkpoint, transparent to data flow: it is recorded
          ;; but does not advance {{prev}} or contribute to {{step-*}}.
          (let [gate? (gate-step? step)]
            (recur more (inc idx)
                   (if gate? prev (:text res))
                   (if gate? outs (assoc outs (:id step) (:text res)))
                   acc (conj results res))))))))

(defn- run-chain-dag
  [config steps {:keys [stdin args max-cost-usd verbosity run-id seed-outputs
                        yes]}]
  (let [steps (vec (with-ids steps))
        waves (topological-waves steps)]
    (loop [[wave & more] waves, outs (or seed-outputs {}), acc 0.0, results []]
      (if (nil? wave)
        (let [last-step (last steps)]
          (finalize {:run-id run-id :plan {:steps steps} :results results :ok? true
                     :final-text (get outs (:id last-step))}))
        (let [_   (v/step verbosity (str "wave [" (str/join " " (map :id wave)) "]"))
              ctx {:stdin stdin :prev stdin :args args :step-outputs outs :yes yes}
              res-pairs (doall (pmap (fn [s] [s (run-step config s ctx)]) wave))
              new-results (into results (map second res-pairs))
              acc (+ acc (reduce + 0.0 (map (comp step-cost second) res-pairs)))
              failed (first (filter (comp not :ok? second) res-pairs))]
          (doseq [[s rr] res-pairs]
            (v/detail verbosity (str (:id s)) (:text rr)))
          (cond
            failed
            (finalize {:run-id run-id :plan {:steps steps} :results new-results
                       :ok? false :failed-at (:id (first failed))
                       :error (:error (second failed))})
            (over-cap? max-cost-usd acc)
            (finalize {:run-id run-id :plan {:steps steps} :results new-results
                       :ok? false :failed-at (:id (first wave))
                       :error {:type :cost-cap
                               :message (format "cost cap $%.4f exceeded ($%.4f)"
                                                (double max-cost-usd) acc)}})
            :else
            (recur more
                   ;; gates are transparent — recorded, but not in {{step-*}}.
                   (into outs (keep (fn [[s rr]]
                                      (when-not (gate-step? s)
                                        [(:id s) (:text rr)]))
                                    res-pairs))
                   acc new-results)))))))

(defn run-chain
  "Unified entry. Linear when no step declares :depends-on, else DAG (waves).
   opts: {:steps :stdin :args :max-cost-usd :verbosity :run-id}.
   Returns a ChainResult."
  [config {:keys [steps] :as opts}]
  (let [run-id (or (:run-id opts) (str (java.util.UUID/randomUUID)))
        opts   (assoc opts :run-id run-id)
        cr     (if (linear? steps)
                 (run-chain-linear config steps opts)
                 (run-chain-dag config steps opts))]
    ;; Persist stdin/args in the plan so cw replay can re-substitute faithfully.
    (assoc cr :plan (merge (:plan cr)
                           {:stdin (:stdin opts) :args (:args opts)}))))
