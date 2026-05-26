(ns cw.parse
  "Provider stdout/stderr → cw.result/Result. defmulti on :parser keyword.

   Input map: {:parser :claude-json|:gemini-json|:codex-jsonl|:raw|:shell-cmd
               :stdout str :stderr str :exit int :duration-ms long
               :provider kw :model str :parser-cmd [..] (shell-cmd only)}"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.process :as p]
            [cw.result :as r]))

(defmulti parse :parser)

(defn- json-parse [s]
  (try (json/parse-string s true)
       (catch Exception _ nil)))

(defn- exec-error [{:keys [provider model exit stderr stdout duration-ms]}]
  (r/result {:provider provider :model model :ok? false
             :duration-ms duration-ms
             :error {:type :exec
                     :message (str "exit " exit
                                   (when-let [e (not-empty (str/trim (or stderr "")))]
                                     (str ": " (subs e 0 (min 300 (count e))))))
                     :details {:exit exit}}
             :raw (or stdout stderr)}))

;; ── claude: single JSON object on stdout ─────────────────────────────────────
;; {type:"result" subtype:"success" is_error:false result:"..." session_id:..
;;  total_cost_usd:.. usage:{input_tokens,output_tokens,cache_read_input_tokens}}
(defmethod parse :claude-json
  [{:keys [stdout provider model duration-ms] :as in}]
  (let [m (json-parse stdout)]
    (cond
      (nil? m) (if (zero? (:exit in))
                 (r/result {:provider provider :model model :ok? false
                            :duration-ms duration-ms
                            :error {:type :parse :message "claude: stdout not JSON"}
                            :raw stdout})
                 (exec-error in))
      (:is_error m)
      (r/result {:provider provider :model (or (:model m) model) :ok? false
                 :duration-ms (or (:duration_ms m) duration-ms)
                 :session-id (:session_id m)
                 :error {:type :provider
                         :message (or (:result m)
                                      (:api_error_status m)
                                      "claude reported is_error")
                         :details (select-keys m [:subtype :api_error_status])}
                 :raw m})
      :else
      (let [u (:usage m)]
        (r/result {:provider provider :model (or (:model m) model)
                   :text (str (:result m)) :ok? true
                   :duration-ms (or (:duration_ms m) duration-ms)
                   :session-id (:session_id m)
                   :cost-usd (:total_cost_usd m)
                   :usage (when u {:input-tokens (:input_tokens u)
                                   :output-tokens (:output_tokens u)
                                   :cached-input-tokens (:cache_read_input_tokens u)})
                   :raw m})))))

;; ── gemini: single JSON object; {response, stats, session_id} on success,
;;    {session_id, error:{type,message,code}} on failure (may be on stderr). ──
(defmethod parse :gemini-json
  [{:keys [stdout stderr provider model duration-ms] :as in}]
  (let [m (or (json-parse stdout) (json-parse stderr))]
    (cond
      (nil? m) (if (zero? (:exit in))
                 (r/result {:provider provider :model model :ok? false
                            :duration-ms duration-ms
                            :error {:type :parse :message "gemini: no JSON found"}
                            :raw (or stdout stderr)})
                 (exec-error in))
      (:error m)
      (let [e (:error m)
            rate? (re-find #"(?i)rate.?limit|quota|resource.?exhausted"
                           (str (:message e)))]
        (r/result {:provider provider :model model :ok? false
                   :duration-ms duration-ms
                   :session-id (:session_id m)
                   :error {:type :provider
                           :message (or (:message e) "gemini error")
                           :details {:code (:code e)
                                     :rate-limit (boolean rate?)}}
                   :raw m}))
      :else
      (let [stats (:stats m)
            tok   (or (get-in stats [:models])
                      (get-in stats [:metrics :tokens])
                      (:tokens stats))
            in-t  (some #(get tok %) [:input :prompt :input_tokens :promptTokenCount])
            out-t (some #(get tok %) [:output :candidates :output_tokens :candidatesTokenCount])]
        (r/result {:provider provider :model (or (:model m) model)
                   :text (str (or (:response m) (:text m))) :ok? true
                   :duration-ms duration-ms
                   :session-id (:session_id m)
                   :usage (when (or in-t out-t)
                            {:input-tokens in-t :output-tokens out-t
                             :cached-input-tokens nil})
                   :raw m})))))

;; ── codex: NDJSON event stream on stdout; reduce to final state. ─────────────
;; UNVERIFIED against a real codex CLI (not installed locally). Reducer is
;; defensive: collects assistant text, last-seen usage, session id.
(defn- codex-reduce [lines]
  (reduce
   (fn [acc line]
     (if-let [ev (json-parse line)]
       (let [t (:type ev)]
         (cond
           (and (= t "item.completed")
                (= "assistant_message" (get-in ev [:item :type])))
           (update acc :text str (or (get-in ev [:item :text])
                                     (get-in ev [:item :content]) ""))

           (or (= t "turn.completed") (:usage ev))
           (let [u (or (:usage ev) (get-in ev [:turn :usage]))]
             (cond-> acc
               u (assoc :usage {:input-tokens (or (:input_tokens u) (:prompt_tokens u))
                                :output-tokens (or (:output_tokens u) (:completion_tokens u))
                                :cached-input-tokens (:cached_input_tokens u)})))

           (:session_id ev) (assoc acc :session-id (:session_id ev))

           (or (= t "error") (:error ev))
           (assoc acc :error (or (:message ev) (str (:error ev)) "codex error"))

           :else acc))
       acc))
   {:text ""}
   lines))

(defmethod parse :codex-jsonl
  [{:keys [stdout provider model duration-ms] :as in}]
  (let [lines (remove str/blank? (str/split-lines (or stdout "")))]
    (if (empty? lines)
      (if (zero? (:exit in))
        (r/result {:provider provider :model model :ok? false
                   :duration-ms duration-ms
                   :error {:type :parse :message "codex: empty output"}
                   :raw stdout})
        (exec-error in))
      (let [{:keys [text usage session-id error]} (codex-reduce lines)]
        (if error
          (r/result {:provider provider :model model :ok? false
                     :duration-ms duration-ms
                     :error {:type :provider :message error}
                     :raw stdout})
          (r/result {:provider provider :model model
                     :text (str/trim (str text)) :ok? true
                     :duration-ms duration-ms
                     :session-id session-id :usage usage
                     :raw stdout}))))))

;; ── qwen: JSON ARRAY of events; the terminal {type:"result"} carries the
;;    text, usage, and outcome (per QwenLM/qwen-code headless docs). Shape:
;;    [{type:"system" subtype:"init" ...}
;;     {type:"assistant" message:{content:[{type:"text" text:"..."}] usage:{...}}}
;;     {type:"result" subtype:"success" is_error:bool result:"..."
;;                    duration_ms:N usage:{input_tokens output_tokens
;;                    cache_read_input_tokens total_tokens} session_id:...}]
(defmethod parse :qwen-json
  [{:keys [stdout provider model duration-ms] :as in}]
  (let [events (json-parse stdout)
        result (when (sequential? events)
                 (first (filter #(= "result" (:type %)) events)))]
    (cond
      (nil? result) (if (zero? (:exit in))
                      (r/result {:provider provider :model model :ok? false
                                 :duration-ms duration-ms
                                 :error {:type :parse
                                         :message "qwen: no terminal result event"}
                                 :raw stdout})
                      (exec-error in))
      (:is_error result)
      (r/result {:provider provider :model model :ok? false
                 :duration-ms (or (:duration_ms result) duration-ms)
                 :session-id (:session_id result)
                 :error {:type :provider
                         :message (or (:result result) "qwen reported is_error")}
                 :raw events})
      :else
      (let [u (:usage result)]
        (r/result {:provider provider :model model
                   :text (str/trim (str (:result result))) :ok? true
                   :duration-ms (or (:duration_ms result) duration-ms)
                   :session-id (:session_id result)
                   :usage (when u {:input-tokens (or (:input_tokens u) (:prompt_tokens u))
                                   :output-tokens (or (:output_tokens u) (:completion_tokens u))
                                   :cached-input-tokens (:cache_read_input_tokens u)})
                   :raw events})))))

;; ── raw: stdout IS the answer. ───────────────────────────────────────────────
(defmethod parse :raw
  [{:keys [stdout provider model duration-ms] :as in}]
  (if (zero? (:exit in))
    (r/result {:provider provider :model model
               :text (str/trim (str stdout)) :ok? true
               :duration-ms duration-ms :raw stdout})
    (exec-error in)))

;; ── shell-cmd: pipe stdout through a user command; its stdout is :text. ──────
;; The extension point: custom provider + :parser :shell-cmd + :parser-cmd.
(defmethod parse :shell-cmd
  [{:keys [stdout exit duration-ms parser-cmd provider model] :as in}]
  (if (zero? exit)
    (let [{:keys [out err exit]} @(p/process parser-cmd
                                             {:in stdout :out :string :err :string})]
      (if (zero? exit)
        (r/result {:provider provider :model model :text (str/trim out) :ok? true
                   :duration-ms duration-ms
                   :raw {:parser-cmd parser-cmd :raw-stdout stdout}})
        (r/result {:provider provider :model model :ok? false
                   :duration-ms duration-ms
                   :error {:type :parse :message (str "parser failed: " err)}
                   :raw stdout})))
    (exec-error in)))

(defmethod parse :default
  [{:keys [parser provider model duration-ms]}]
  (r/result {:provider provider :model model :ok? false :duration-ms duration-ms
             :error {:type :parse :message (str "unknown parser " (pr-str parser))}}))
