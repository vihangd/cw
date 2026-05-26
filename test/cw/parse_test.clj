(ns cw.parse-test
  (:require [clojure.test :refer [deftest is]]
            [cw.parse :as parse]))

(defn- fixture [n] (slurp (str "test/cw/fixtures/" n)))

(deftest claude-success
  (let [r (parse/parse {:parser :claude-json :exit 0 :duration-ms 10
                        :provider :claude :model "m"
                        :stdout (fixture "claude-success.json")})]
    (is (:ok? r))
    (is (= "hi" (:text r)))
    (is (= 0.10434749999999998 (:cost-usd r)))
    (is (= 2 (get-in r [:usage :input-tokens])))
    (is (= "89d624b8-11f8-4489-b8c7-034a452bca12" (:session-id r)))))

(deftest claude-error
  (let [r (parse/parse {:parser :claude-json :exit 0 :duration-ms 10
                        :provider :claude :model "m"
                        :stdout (fixture "claude-error.json")})]
    (is (not (:ok? r)))
    (is (= :provider (get-in r [:error :type])))
    (is (re-find #"Credit balance" (get-in r [:error :message])))))

(deftest claude-non-json-exec-failure
  (let [r (parse/parse {:parser :claude-json :exit 1 :duration-ms 5
                        :provider :claude :model "m"
                        :stdout "" :stderr "boom"})]
    (is (not (:ok? r)))
    (is (= :exec (get-in r [:error :type])))))

(deftest gemini-success
  (let [r (parse/parse {:parser :gemini-json :exit 0 :duration-ms 10
                        :provider :gemini :model "m"
                        :stdout (fixture "gemini-success.json")})]
    (is (:ok? r))
    (is (= "hi" (:text r)))))

(deftest gemini-error-on-stderr
  (let [r (parse/parse {:parser :gemini-json :exit 41 :duration-ms 10
                        :provider :gemini :model "m"
                        :stdout "" :stderr (fixture "gemini-error.json")})]
    (is (not (:ok? r)))
    (is (= :provider (get-in r [:error :type])))
    (is (= 41 (get-in r [:error :details :code])))))

(deftest qwen-success
  ;; Fixture is the real captured output of `qwen -p "reply with exactly: hi"
  ;; --output-format json` against qwen-code 0.16.1.
  (let [r (parse/parse {:parser :qwen-json :exit 0 :duration-ms 1
                        :provider :qwen :model "m"
                        :stdout (fixture "qwen-success.json")})]
    (is (:ok? r))
    (is (= "hi" (:text r)) "trims the leading whitespace qwen emits")
    (is (= 22723 (get-in r [:usage :input-tokens])))
    (is (= 227   (get-in r [:usage :output-tokens])))
    (is (= 3968  (get-in r [:usage :cached-input-tokens])))
    (is (= "2853d4ec-f4d9-4cb2-9c85-0dcf8462c9c5" (:session-id r)))
    (is (= 5819 (:duration-ms r)) "duration_ms from the terminal result event")))

(deftest qwen-error-event
  ;; Synthesize a terminal :result with is_error=true and assert the parser
  ;; lifts it to a :provider error (mirrors :claude-json's is_error path).
  (let [r (parse/parse {:parser :qwen-json :exit 0 :duration-ms 1
                        :provider :qwen :model "m"
                        :stdout "[{\"type\":\"result\",\"is_error\":true,\"result\":\"rate limit\",\"session_id\":\"x\"}]"})]
    (is (not (:ok? r)))
    (is (= :provider (get-in r [:error :type])))
    (is (= "rate limit" (get-in r [:error :message])))))

(deftest qwen-no-result-event
  ;; Defensive: a stream that never contains a :result event should not
  ;; pretend success — parse error.
  (let [r (parse/parse {:parser :qwen-json :exit 0 :duration-ms 1
                        :provider :qwen :model "m"
                        :stdout "[{\"type\":\"system\",\"subtype\":\"init\"}]"})]
    (is (not (:ok? r)))
    (is (= :parse (get-in r [:error :type])))))

(deftest raw-parser
  (is (= "answer" (:text (parse/parse {:parser :raw :exit 0 :duration-ms 1
                                       :provider :x :stdout "  answer\n"})))))

(deftest shell-cmd-parser
  (let [r (parse/parse {:parser :shell-cmd :exit 0 :duration-ms 1
                        :provider :x :model nil
                        :stdout "{\"v\":\"deep\"}"
                        :parser-cmd ["jq" "-r" ".v"]})]
    ;; jq may not be installed everywhere; only assert when it ran cleanly.
    (when (:ok? r) (is (= "deep" (:text r))))))

;; CODEX: parser ships UNVERIFIED against a real codex CLI (not installable
;; here). This exercises the NDJSON reducer against a HAND-AUTHORED fixture
;; only — it proves the reduce logic, not real-format accuracy. See README
;; "Known limitations".
(deftest ^:codex-unverified codex-reducer-handauthored
  (let [r (parse/parse {:parser :codex-jsonl :exit 0 :duration-ms 10
                        :provider :codex :model "m"
                        :stdout (fixture "codex-success.jsonl")})]
    (is (:ok? r))
    (is (= "hi" (:text r)))
    (is (= 11 (get-in r [:usage :input-tokens])))))
