(ns cw.fanout
  "One prompt template (or workflow) → N inputs, bounded concurrency.
   --inputs <glob>        : each matched file's content is {{stdin}} (gap #8)
   --inputs-file <edn>    : EDN vector of ctx maps {:stdin .. :args [..]}
   --prompt-file <path>   : template (with -p PROVIDER), OR
   --workflow <name>      : run a named workflow per input"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [cw.chain :as chain]
            [cw.workflow :as workflow]
            [cw.config :as config]
            [cw.result :as r]))

(defn- inputs [opts]
  (cond
    (:inputs-file opts) (edn/read-string (slurp (:inputs-file opts)))
    (:inputs opts) (let [pat  (:inputs opts)
                         ;; glob relative to the literal directory prefix so
                         ;; absolute patterns (e.g. /tmp/x/*.txt) work.
                         root (or (fs/parent pat) ".")
                         g    (str (fs/file-name pat))]
                     (->> (fs/glob root g)
                          (map #(hash-map :stdin (slurp (str %))
                                          :label (str (fs/file-name %))))))
    :else (throw (ex-info "fanout needs --inputs or --inputs-file" {}))))

(defn- bounded-pmap
  "Runs f over coll with at most n concurrent invocations. pmap alone is
   bounded by the CPU count, not n; a Semaphore enforces the configured cap
   (the point of :fanout-concurrency — rate-limit avoidance)."
  [n f coll]
  (let [sem (java.util.concurrent.Semaphore. (max 1 n))]
    (doall
     (pmap (fn [x]
             (.acquire sem)
             (try (f x) (finally (.release sem))))
           coll))))

(defn run [config opts]
  (let [conc (or (:fanout-concurrency config) 4)
        items (vec (inputs opts))
        run-one
        (fn [ctx]
          (cond
            (:workflow opts)
            (workflow/run-workflow config (:workflow opts)
                                   (or (:args ctx) [])
                                   (assoc opts :stdin (:stdin ctx)))
            (:prompt-file opts)
            (let [tpl (slurp (:prompt-file opts))
                  prov (:key (config/resolve-provider config (:provider opts)))]
              (chain/run-chain config
                               {:steps [{:provider prov :prompt tpl}]
                                :stdin (:stdin ctx) :args (or (:args ctx) [])
                                :verbosity (:verbose opts)}))
            :else (throw (ex-info "fanout needs --prompt-file or --workflow" {}))))
        safe-run (fn [ctx]
                   (try (run-one ctx)
                        (catch Throwable e
                          (r/chain-result
                           {:ok? false :final-text ""
                            :error {:type :exec
                                    :message (str (.getMessage e))}}))))
        results (vec (bounded-pmap conc
                                   (fn [ctx] [(:label ctx) (safe-run ctx)])
                                   items))
        ok (count (filter (comp :ok? second) results))
        total (reduce + 0.0 (keep (comp :total-cost-usd second) results))
        body (str/join "\n\n"
                       (for [[label cr] results]
                         (format "── %s%s ──\n%s"
                                 (or label "input")
                                 (if (:ok? cr) "" " (FAILED)")
                                 (str/trim (str (:final-text cr))))))]
    (r/result {:provider :cw :ok? (= ok (count results))
               :text body
               :cost-usd (when (pos? total) total)
               :raw {:total (count results) :ok ok}})))
