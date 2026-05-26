(ns cw.provider-test
  "Hermetic provider tests: build-command shaping, error classification,
   and the fallback loop driven by local shell binaries (no network)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cw.provider :as provider]))

(def build-command #'cw.provider/build-command)
(def retryable?    #'cw.provider/retryable?)
(def classify      #'cw.provider/classify)

(deftest build-command-arg-vs-prompt-flag
  (testing "bare positional prompt (claude-style)"
    (let [{:keys [cmd stdin-text]}
          (build-command {:cmd "claude" :args ["-p"] :prompt-via :arg
                          :key :claude :model "dm"}
                         {:prompt "hello"})]
      (is (nil? stdin-text))
      (is (= "claude" (first cmd)))
      (is (= "hello" (last cmd)))
      (is (some #{"--model"} cmd))))
  (testing "prompt-flag (gemini-style -p)"
    (let [{:keys [cmd]} (build-command
                         {:cmd "gemini" :args [] :prompt-via :arg
                          :prompt-flag "-p" :key :gemini}
                         {:prompt "q"})]
      (is (= ["gemini" "-p" "q"] cmd))))
  (testing "argv-limit overflow falls back to stdin"
    (let [big (apply str (repeat 20000 "x"))
          {:keys [cmd stdin-text]}
          (build-command {:cmd "claude" :args [] :prompt-via :arg :key :claude}
                         {:prompt big})]
      (is (= big stdin-text))
      (is (not (some #{big} cmd))))))

(deftest build-command-schema
  (testing "native schema → passthrough flag"
    (let [{:keys [cmd]} (build-command
                         {:cmd "codex" :args [] :prompt-via :arg :key :codex
                          :supports-schema true :schema-flag "--output-schema"}
                         {:prompt "p" :schema "/tmp/s.json"})]
      (is (= ["--output-schema" "/tmp/s.json"]
             (->> cmd (drop-while #(not= % "--output-schema")) (take 2))))))
  (testing "non-native schema → prompt-embed"
    (let [f (java.io.File/createTempFile "schema" ".json")]
      (spit f "{\"type\":\"object\"}")
      (let [{:keys [cmd]} (build-command
                           {:cmd "claude" :args [] :prompt-via :arg :key :claude
                            :supports-schema false}
                           {:prompt "P" :schema (.getPath f)})]
        (is (re-find #"valid JSON matching this schema" (last cmd)))
        (is (re-find #"\{\"type\":\"object\"\}" (last cmd))))
      (.delete f))))

(deftest build-command-claude-tools
  (let [{:keys [cmd]} (build-command
                       {:cmd "claude" :args [] :prompt-via :arg :key :claude}
                       {:prompt "p" :allowed-tools [:read :edit :bash]})]
    (is (= ["--allowedTools" "Read,Edit,Bash"]
           (->> cmd (drop-while #(not= % "--allowedTools")) (take 2))))))

(deftest classify-and-retryable
  (is (= [true nil]  (retryable? {:type :timeout})))
  (is (= [true nil]  (retryable? {:type :exec})))
  (is (= [false nil] (retryable? {:type :parse})))
  (is (= [false nil] (retryable? {:type :cost-cap})))
  (is (= [false nil] (retryable? {:type :provider
                                  :message "400 bad request invalid"})))
  (is (= [true :out-of-credits]
         (retryable? {:type :provider :message "credit balance too low"})))
  (is (= [true :rate-limit]
         (retryable? {:type :provider :message "429 rate limit"})))
  (is (= :rate-limit (classify {:type :provider
                                :details {:rate-limit true} :message ""}))))

;; ── fallback loop (hermetic: shell binaries, temp state) ────────────────────
(def ^:dynamic *cfg* nil)

(use-fixtures :each
  (fn [t]
    (let [f (java.io.File/createTempFile "cw-pstate" ".edn")]
      (.delete f)
      (binding [*cfg*
                {:state-file (.getPath f)
                 :blacklist-threshold 99
                 :providers
                 {:bad   {:cmd "false" :args [] :parser :raw
                          :prompt-via :arg :alias []}
                  :good  {:cmd "true" :args [] :parser :raw
                          :prompt-via :arg :alias []}
                  :gone  {:cmd "cw-no-such-binary-xyz" :args []
                          :parser :raw :prompt-via :arg :alias []}}}]
        (t))
      (.delete f))))

(deftest fallback-success-after-retryable-failure
  (let [r (provider/run *cfg* {:provider :bad :prompt "x"
                               :fallback [:good]})]
    (is (:ok? r))
    (is (= :good (:provider r)))
    (is (= 2 (count (:fallback-chain r))))
    (is (= :bad (:provider (first (:fallback-chain r)))))))

(deftest fallback-handles-missing-binary
  (let [r (provider/run *cfg* {:provider :gone :prompt "x"
                               :fallback [:good]})]
    (is (:ok? r))
    (is (= :good (:provider r)))))

(deftest fallback-exhausted
  (let [r (provider/run *cfg* {:provider :bad :prompt "x"
                               :fallback [:gone]})]
    (is (not (:ok? r)))
    (is (= :fallback-exhausted (get-in r [:error :type])))
    (is (= 2 (count (:fallback-chain r))))))

(deftest no-fallback-disables-chain
  ;; :no-fallback drops the [:good] candidate entirely — only :bad is tried.
  (let [r (provider/run *cfg* {:provider :bad :prompt "x"
                               :no-fallback true :fallback [:good]})]
    (is (not (:ok? r)))
    (is (= 1 (count (:fallback-chain r))))
    (is (= :bad (:provider (first (:fallback-chain r)))))))
