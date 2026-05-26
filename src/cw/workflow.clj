(ns cw.workflow
  "Resolves a named workflow into a chain run: gathers stdin (piped >
   :stdin-cmd > nil), binds positional args, delegates to cw.chain."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [cw.config :as config]
            [cw.chain :as chain]))

;; (System/console) is nil when stdin is piped (not a TTY).
(defn piped-stdin []
  (when (nil? (System/console))
    (let [s (slurp *in*)]
      (when-not (str/blank? s) s))))

(defn- stdin-cmd-output [cmd]
  (let [{:keys [out exit]} @(p/process cmd {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn resolve-stdin
  "Precedence: explicitly piped stdin > workflow :stdin-cmd > nil."
  [workflow piped]
  (cond
    (and piped (not (str/blank? piped))) piped
    (:stdin-cmd workflow) (stdin-cmd-output (:stdin-cmd workflow))
    :else nil))

(defn run-workflow
  "Runs workflow `name` with positional `args`. opts may carry :provider /
   :model overrides, :max-cost-usd, :verbose, :stdin (pre-read)."
  [config name args opts]
  (let [wf    (config/resolve-workflow config name)
        _     (when-not wf
                (throw (ex-info (str "Unknown workflow: " name) {:workflow name})))
        stdin (resolve-stdin wf (or (:stdin opts) (piped-stdin)))
        ;; CLI overrides applied to every LLM step
        ov    (fn [step]
                (cond-> step
                  (and (:provider opts) (not (:cmd step)))
                  (assoc :provider (:provider opts))
                  (and (:model opts) (not (:cmd step)))
                  (assoc :model (:model opts))
                  (:no-fallback opts) (assoc :no-fallback true)))
        steps (mapv ov (:steps wf))]
    (chain/run-chain config
                     {:steps steps
                      :stdin stdin
                      :args (vec args)
                      :max-cost-usd (or (:max-cost-usd opts)
                                        (:max-cost-usd-per-run config))
                      :verbosity (:verbose opts)
                      :yes (:yes opts)})))
