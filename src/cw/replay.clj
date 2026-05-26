(ns cw.replay
  "Re-runs a logged chain. The original run is preserved; replay gets a new
   run-id. Supports provider/model override and --step slicing (earlier step
   outputs are seeded from the logged results)."
  (:require [clojure.string :as str]
            [cw.config :as config]
            [cw.chain :as chain]
            [cw.log :as log]))

(defn- assoc-each-llm [steps k v]
  (mapv #(if (:cmd %) % (assoc % k v)) steps))

(defn- step-index
  "Resolves --step (1-based index or :id) to a 0-based index into steps."
  [steps s]
  (when s
    (let [s (str s)]
      (if-let [n (parse-long s)]
        (dec n)
        (first (keep-indexed
                (fn [i st] (when (= (keyword s) (:id st)) i)) steps))))))

(defn run
  [config run-id {:keys [provider model step] :as opts}]
  (let [record (log/load-run config run-id)
        _      (when-not record
                 (throw (ex-info (str "no such run: " run-id) {:run-id run-id})))
        cr     (:chain-result record)
        plan   (:plan cr)
        steps  (vec (:steps plan))
        prov   (when provider (:key (config/resolve-provider config provider)))
        steps  (cond-> steps
                 prov  (assoc-each-llm :provider prov)
                 model (assoc-each-llm :model model))
        idx    (step-index steps step)
        logged (:results cr)
        ;; seed outputs from steps before the slice point
        seed   (when idx
                 (into {} (keep-indexed
                           (fn [i st]
                             (when (< i idx)
                               [(or (:id st) (keyword (str "step-" (inc i))))
                                (:text (nth logged i nil))]))
                           steps)))
        slice  (if idx (subvec steps idx) steps)
        seed-prev (when (and idx (pos? idx))
                    (:text (nth logged (dec idx) nil)))]
    (chain/run-chain config
                     {:steps slice
                      :stdin (:stdin plan)
                      :args (:args plan)
                      :seed-outputs seed
                      :seed-prev seed-prev
                      :max-cost-usd (:max-cost-usd opts)
                      :verbosity (:verbose opts)
                      :yes (:yes opts)})))
