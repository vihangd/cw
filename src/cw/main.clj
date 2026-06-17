(ns cw.main
  "Entry, arg parsing, dispatch, Unix-correct output."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [cw.config :as config]
            [cw.chain :as chain]
            [cw.workflow :as workflow]
            [cw.result :as r]
            [cw.verbosity :as v]))

(def cli-spec
  {:provider     {:alias :p}
   :model        {:alias :m}
   :max-turns    {:coerce :long}
   :timeout      {:coerce :long}
   :max-cost-usd {:coerce :double}
   :schema       {}
   :no-fallback  {:coerce :boolean}
   :yes          {:alias :y :coerce :boolean}
   :cost         {:coerce :boolean}
   :json         {:coerce :boolean}
   :dry-run      {:coerce :boolean}
   :no-log       {:coerce :boolean}
   :result-codes {:coerce :boolean}
   :runs         {:coerce :long :default 5}
   :limit        {:coerce :long}
   :fixture      {}
   :inputs       {}
   :prompt-file  {}
   :inputs-file  {}
   :workflow     {}
   :all          {:coerce :boolean}
   :step         {}})

(defn- extract-verbosity
  "babashka.cli doesn't count repeated short flags; strip -v/-vv ourselves."
  [args]
  (reduce (fn [[lvl acc] a]
            (cond
              (= a "-v")  [(+ lvl 1) acc]
              (= a "-vv") [(+ lvl 2) acc]
              :else       [lvl (conj acc a)]))
          [0 []]
          args))

(defn- opt-provider [config opts]
  (when (:provider opts)
    (:key (config/resolve-provider config (:provider opts)))))

;; ── output ──────────────────────────────────────────────────────────────────
(defn- chain-result? [x] (instance? cw.result.ChainResult x))

(defn handle-output [result opts]
  (cond
    (:json opts)
    ;; deep-convert nested Result records so the EDN is plain maps, not
    ;; #cw.result.Result tagged literals.
    (println (pr-str (walk/postwalk #(if (record? %) (into {} %) %) result)))

    (chain-result? result)
    (do (when (:final-text result) (println (:final-text result)))
        (when-not (:ok? result)
          (v/warn (str "failed at " (:failed-at result) ": "
                       (get-in result [:error :message]))))
        (v/cost-line (:verbose opts) (:cost opts) (:total-cost-usd result)))

    :else ; Result
    (do (when (:text result) (println (:text result)))
        (when-not (:ok? result)
          (v/warn (get-in result [:error :message])))
        (v/cost-line (:verbose opts) (:cost opts) (:cost-usd result)))))

;; ── chain subcommand parsing ────────────────────────────────────────────────
(defn- num-opt [m k]
  (let [v (get m k)]
    (cond (number? v) v
          (string? v) (try (Double/parseDouble v) (catch Exception _ nil))
          :else nil)))

(defn step-opts
  "Global CLI flags that apply per LLM step (chain ad-hoc + steps-file).
   Handles both string (split-chain-args) and coerced (cli) values."
  [opts]
  (cond-> {}
    (:model opts)       (assoc :model (:model opts))
    (:no-fallback opts) (assoc :no-fallback true)
    (:schema opts)      (assoc :schema (:schema opts))
    (num-opt opts :timeout)
    (assoc :timeout-ms (long (* 1000 (num-opt opts :timeout))))))

(defn- apply-step-opts [steps so]
  ;; Skip shell, gate, and phase-splice steps — only LLM steps consume
  ;; :model/:timeout-ms/:no-fallback/:schema.
  (mapv #(if (or (:cmd %) (contains? % :gate) (:use %)) % (merge % so)) steps))

(defn- run-chain-cmd [config tail opts]
  (let [default-p (or (opt-provider config opts) (:default-provider config))
        so    (step-opts opts)
        steps (if-let [sf (:steps-file opts)]
                (apply-step-opts (:steps (edn/read-string (slurp sf))) so)
                (chain/parse-chain-steps config tail default-p so))
        stdin (workflow/piped-stdin)]
    (chain/run-chain config
                     {:steps steps :stdin stdin :args (vec tail)
                      :max-cost-usd (or (num-opt opts :max-cost-usd)
                                        (:max-cost-usd-per-run config))
                      :verbosity (:verbose opts)
                      :yes (:yes opts)})))

(defn- run-one-shot [config sub rest-tail opts]
  (let [prompt (str/join " " (cons sub rest-tail))
        stdin  (workflow/piped-stdin)
        prompt (if (and stdin (not (str/includes? prompt "{{stdin}}")))
                 (str prompt "\n\n{{stdin}}")
                 prompt)
        step   (cond-> {:provider (or (opt-provider config opts)
                                      (:default-provider config))
                        :prompt prompt}
                 (:model opts)        (assoc :model (:model opts))
                 (:schema opts)       (assoc :schema (:schema opts))
                 (:max-turns opts)    (assoc :max-turns (:max-turns opts))
                 (:timeout opts)      (assoc :timeout-ms (* 1000 (:timeout opts)))
                 (:no-fallback opts)  (assoc :no-fallback true))]
    (chain/run-chain config {:steps [step] :stdin stdin :args []
                             :max-cost-usd (:max-cost-usd opts)
                             :verbosity (:verbose opts)
                             :yes (:yes opts)})))

;; ── meta commands (resolved lazily so M4/M5 modules just need to exist) ──────
(defn- call [sym & args]
  (apply (requiring-resolve sym) args))

(defn- print-providers [config]
  (doseq [[k v] (:providers config)]
    (println (format "%-10s %s  model=%s  alias=%s"
                     (name k) (:cmd v) (:model v)
                     (str/join "," (map name (:alias v))))))
  (r/ok-result))

(defn- print-workflows [config]
  (doseq [[k v] (:workflows config)]
    (println (format "%-22s %s" (name k) (or (:doc v) ""))))
  (r/ok-result))

(defn- one-line [s]
  (let [t (str/replace (str s) #"\s+" " ")]
    (subs t 0 (min 70 (count t)))))

(defn- print-library [config section]
  (doseq [[k v] (sort-by key (get config section))]
    (println (format "%-22s %s" (name k)
                     (cond (map? v) (str "→ " (:file v))
                           :else (one-line v)))))
  (r/ok-result))

(defn- print-phases [config]
  (doseq [[k v] (:phases config)]
    (println (format "%-16s %s  (%d steps)" (name k) (or (:doc v) "")
                     (count (:steps v)))))
  (r/ok-result))

(defn- handle-config-cmd [sub]
  (case sub
    "path" (do (println (config/config-path)) (r/ok-result))
    "init" (let [{:keys [written path reason prompts]} (config/write-starter-config!)]
             (println (if written
                        (str "wrote " path
                             (when (seq prompts)
                               (str " + " (count prompts) " prompt file(s)")))
                        (str path " (" (name reason) ")")))
             (r/ok-result))
    (do (println "usage: cw config path|init") (r/ok-result))))

(defn- handle-runs-cmd [config tail opts]
  (case (first tail)
    "list" (call 'cw.log/print-list config opts)
    "show" (call 'cw.log/print-show config (second tail))
    (do (println "usage: cw runs list|show <id>") (r/ok-result))))

(defn- dispatch [config sub tail opts]
  (case sub
    nil         (r/ok-result "usage: cw [PROMPT] | cw <workflow> | cw chain ...")
    "providers" (print-providers config)
    "workflows" (print-workflows config)
    "roles"     (print-library config :roles)
    "skills"    (print-library config :skills)
    "fragments" (print-library config :fragments)
    "phases"    (print-phases config)
    "config"    (handle-config-cmd (first tail))
    "doctor"    (call 'cw.doctor/run config)
    "graph"     (call 'cw.mermaid/run config tail opts)
    "chain"     (run-chain-cmd config tail opts)
    "compare"   (call 'cw.compare/run config (first tail) opts)
    "fanout"    (call 'cw.fanout/run config opts)
    "eval"      (call 'cw.eval/run config (first tail) opts)
    "replay"    (call 'cw.replay/run config (first tail) opts)
    "runs"      (handle-runs-cmd config tail opts)
    (if (config/resolve-workflow config sub)
      (workflow/run-workflow config sub tail opts)
      (run-one-shot config sub tail opts))))

(defn- maybe-dry-run [config sub tail opts]
  (when (:dry-run opts)
    (call 'cw.dryrun/render config sub tail opts)))

(defn- maybe-log! [config result opts raw-args]
  (when (and (chain-result? result)
             (:log-runs? config)
             (not (:no-log opts))
             (:run-id result))
    (try (call 'cw.log/write-run! config result raw-args)
         (catch Throwable _ nil))))

;; chain takes -p/--provider per-step (§2), so its step tokens must NOT pass
;; through global cli parsing. Pull recognized global flags, keep the rest raw.
(def ^:private chain-value-flags
  #{"--steps-file" "--max-cost-usd" "--model" "-m" "--timeout"})
(def ^:private chain-bool-flags
  #{"--json" "--dry-run" "--no-log" "--no-fallback" "--cost" "--yes" "-y"})

;; short-flag → canonical option key (so chain-tail flags match step-opts)
(def ^:private chain-flag-alias {:y :yes :m :model})

(defn- chain-flag-key [t]
  (let [k (keyword (str/replace t #"^-+" ""))]
    (get chain-flag-alias k k)))

(defn- split-chain-args [tokens]
  (loop [[t & more] tokens, opts {}, steps []]
    (cond
      (nil? t) [opts steps]
      (chain-bool-flags t)
      (recur more (assoc opts (chain-flag-key t) true) steps)
      (chain-value-flags t)
      (recur (rest more) (assoc opts (chain-flag-key t) (first more)) steps)
      :else (recur more opts (conj steps t)))))

;; babashka.cli/parse-args stops option parsing at the first positional, so a
;; global flag placed *before* a subcommand strands flags placed *after* it.
;; We extract recognized global options from anywhere instead — flag position
;; is irrelevant (Unix-correct). The `chain` subcommand is a hard stop: its
;; trailing -p/--provider are per-step and must stay raw for split-chain-args.
(defn- flag-map [spec]
  (reduce (fn [m [k v]]
            (let [e {:k k :bool? (= :boolean (:coerce v)) :coerce (:coerce v)}
                  m (assoc m (str "--" (name k)) e)]
              (if-let [a (:alias v)] (assoc m (str "-" (name a)) e) m)))
          {} spec))

(defn- coerce-val [c s]
  (try (case c :long (Long/parseLong s) :double (Double/parseDouble s) s)
       (catch Exception _ s)))

(defn- parse-global
  "→ [opts positional chain-raw]. chain-raw is the untouched token vector
   after a `chain` subcommand (nil if not a chain invocation)."
  [args spec]
  (let [fm (flag-map spec)]
    (loop [[t & more :as all] (seq args), opts {}, pos []]
      (cond
        (nil? t) [opts pos nil]
        (and (= t "chain") (empty? pos)) [opts ["chain"] (vec more)]
        :else
        (let [[flag inl] (if (and (str/starts-with? t "--")
                                  (str/includes? t "="))
                           (str/split t #"=" 2)
                           [t nil])
              e (get fm flag)]
          (cond
            (nil? e)   (recur more opts (conj pos t))
            (:bool? e) (recur more (assoc opts (:k e) true) pos)
            inl        (recur more (assoc opts (:k e)
                                          (coerce-val (:coerce e) inl)) pos)
            :else      (recur (rest more)
                              (assoc opts (:k e)
                                     (coerce-val (:coerce e) (first more)))
                              pos)))))))

(defn -main [& raw-args]
  (let [[verbosity args] (extract-verbosity raw-args)
        config (config/validate (config/load-config))
        [gopts pos chain-raw] (parse-global args (assoc cli-spec :steps-file {}))
        chain? (= (first pos) "chain")
        [opts tail rest-t]
        (if chain?
          (let [[copts steps] (split-chain-args chain-raw)]
            [(merge gopts copts {:verbose verbosity}) (vec steps) (vec steps)])
          [(assoc gopts :verbose verbosity)
           (vec pos) (vec (rest pos))])
        sub (if chain? "chain" (first tail))]
    (try
      (if-let [dr (maybe-dry-run config sub rest-t opts)]
        (do (println dr) (System/exit 0))
        (let [result (dispatch config sub rest-t opts)]
          (maybe-log! config result opts raw-args)
          (handle-output result opts)
          (System/exit (r/result-exit-code result (:result-codes opts)))))
      (catch clojure.lang.ExceptionInfo e
        (v/warn (.getMessage e))
        (when-let [errs (:errors (ex-data e))]
          (doseq [er errs] (v/warn " -" er)))
        (System/exit 2))
      (catch Throwable e
        (v/warn (str "error: " (.getMessage e)))
        (System/exit 2)))))
