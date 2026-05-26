(ns cw.provider
  "Builds the provider command, runs it (with timeout), parses, and applies the
   fallback chain with persistent state side-effects."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [cheshire.core :as json]
            [cw.config :as config]
            [cw.parse :as parse]
            [cw.state :as state]
            [cw.result :as r]))

(def ^:private argv-limit 16384)

;; Default coarse-tool → CLI-flag mapping for claude. Other built-ins have no
;; headless equivalent; custom providers supply :tool-mapping (gap #6).
(def ^:private claude-tool-names
  {:read "Read" :edit "Edit" :bash "Bash" :web "WebSearch"})

(defn- map-allowed-tools [provider-cfg tools]
  (when (seq tools)
    (case (:key provider-cfg)
      :claude ["--allowedTools"
               (str/join "," (keep claude-tool-names tools))]
      (when-let [m (:tool-mapping provider-cfg)]
        (vec (mapcat #(get m %) tools))))))

(defn- schema-prompt-embed
  "Gap #5: non-native-schema providers get the schema appended to the prompt."
  [prompt schema-path]
  (str prompt
       "\n\nRespond with ONLY valid JSON matching this schema "
       "(no prose, no code fences):\n"
       (slurp schema-path)))

(defn- build-command
  "Returns {:cmd [argv...] :stdin-text str-or-nil}."
  [provider-cfg opts]
  (let [native-schema? (and (:schema opts) (:supports-schema provider-cfg))
        prompt (if (and (:schema opts) (not native-schema?))
                 (schema-prompt-embed (:prompt opts) (:schema opts))
                 (:prompt opts))
        model  (or (:model opts) (:model provider-cfg))
        via    (:prompt-via provider-cfg :arg)
        ;; argv-limit fallback (gap #10): oversized :arg prompt → stdin
        via    (if (and (= via :arg) (>= (count (str prompt)) argv-limit))
                 :stdin via)
        base   (concat [(:cmd provider-cfg)]
                       (:args provider-cfg)
                       (when model [(:model-flag provider-cfg "--model") model])
                       (when (:max-turns opts) ["--max-turns" (str (:max-turns opts))])
                       (when native-schema?
                         [(:schema-flag provider-cfg) (:schema opts)])
                       (map-allowed-tools provider-cfg (:allowed-tools opts))
                       (:extra-args opts))
        argv   (case via
                 :arg   (if-let [pf (:prompt-flag provider-cfg)]
                          (concat base [pf prompt])
                          (concat base [prompt]))
                 :stdin base)]
    {:cmd (vec (remove nil? argv))
     :stdin-text (when (= via :stdin) prompt)}))

(defn- run-once
  "Single provider execution. Returns a Result. Never throws on agent failure."
  [config provider-key opts]
  (let [pcfg (config/resolve-provider config provider-key)
        {:keys [cmd stdin-text]} (build-command pcfg opts)
        timeout-ms (or (:timeout-ms opts) 300000)
        t0   (System/currentTimeMillis)
        proc (try
               (p/process cmd (cond-> {:out :string :err :string}
                                stdin-text (assoc :in stdin-text)
                                (:cwd opts) (assoc :dir (:cwd opts))
                                (:env opts) (assoc :extra-env (:env opts))))
               (catch java.io.IOException e ::launch-failed))
        done (if (= proc ::launch-failed)
               ::launch-failed
               (deref (future @proc) timeout-ms ::timeout))
        dur  (- (System/currentTimeMillis) t0)]
    (cond
      (= done ::launch-failed)
      (r/result {:provider provider-key :model (:model pcfg) :ok? false
                 :duration-ms dur
                 :error {:type :exec
                         :message (str "cannot launch " (first cmd)
                                       " (not found or not executable)")}})

      (= done ::timeout)
      (do (p/destroy-tree proc)
          (r/result {:provider provider-key :model (:model pcfg) :ok? false
                     :duration-ms dur
                     :error {:type :timeout
                             :message (str "timed out after " timeout-ms "ms")}}))

      :else
      (parse/parse {:parser (:parser pcfg)
                    :parser-cmd (:parser-cmd pcfg)
                    :stdout (:out done) :stderr (:err done)
                    :exit (:exit done) :duration-ms dur
                    :provider provider-key
                    :model (or (:model opts) (:model pcfg))}))))

(defn- classify
  "Refines a raw :exec/:provider error using stderr/message heuristics so
   fallback + blacklist decisions are accurate."
  [error]
  (let [msg (str/lower-case (str (:message error)))]
    (cond
      (re-find #"out of credit|insufficient|payment|billing|credit balance" msg)
      :out-of-credits
      (or (:rate-limit (:details error))
          (re-find #"rate.?limit|quota|resource.?exhausted|429|overloaded" msg))
      :rate-limit
      (re-find #"invalid|bad request|unauthorized|forbidden|400|401|403" msg)
      :bad-request
      :else (:type error))))

(defn- retryable?
  "Gap #3. Returns [retry? blacklist-kind] for an error map."
  [error]
  (let [refined (classify error)]
    (case (:type error)
      :timeout [true nil]
      :exec    [true nil]
      :provider (case refined
                  :out-of-credits [true :out-of-credits]
                  :rate-limit     [true :rate-limit]
                  :bad-request    [false nil]
                  [true nil])
      :parse   [false nil]
      :cost-cap [false nil]
      [false nil])))

(defn- fallback-exhausted-result [opts attempted]
  (r/result {:provider (:provider opts) :ok? false :text ""
             :error {:type :fallback-exhausted
                     :message "all providers failed"
                     :details {:attempted attempted}}
             :fallback-chain attempted}))

(defn run
  "Executes a step with optional fallback. Tries primary then each fallback on
   retryable failure, recording state per attempt. Returns a Result with
   :fallback-chain populated. :no-fallback in opts disables fallback."
  [config opts]
  (let [primary  (:provider opts)
        fallback (if (:no-fallback opts)
                   []
                   (or (:fallback opts)
                       (get-in config [:providers primary :fallback])
                       []))
        candidates (cons primary fallback)]
    (loop [[head & more] candidates
           attempted []]
      (if (nil? head)
        (fallback-exhausted-result opts attempted)
        (let [pcfg (config/resolve-provider config head)
              st   (state/load-state config)]
          (if-not (state/can-use? st head pcfg)
            (recur more (conj attempted {:provider head
                                         :skipped :blacklisted-or-capped}))
            (let [res (run-once config head (assoc opts :provider head))]
              (if (:ok? res)
                (do (state/record-success! config head)
                    (assoc res :fallback-chain
                           (conj attempted {:provider head :ok? true})))
                (let [[retry? bl-kind] (retryable? (:error res))]
                  (state/record-failure! config head
                                         (or bl-kind (get-in res [:error :type])))
                  (if retry?
                    (recur more (conj attempted {:provider head
                                                 :error (:error res)}))
                    (assoc res :fallback-chain
                           (conj attempted {:provider head
                                            :error (:error res)}))))))))))))
