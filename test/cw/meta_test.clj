(ns cw.meta-test
  "Coverage for dryrun, log, mermaid, eval helpers, and main/step-opts."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cw.dryrun :as dryrun]
            [cw.mermaid :as mermaid]
            [cw.log :as log]
            [cw.main :as main]
            [cw.eval]
            [cw.result :as r]))

;; ── dryrun ──────────────────────────────────────────────────────────────────
(def dcfg
  {:default-provider :claude
   :providers {:claude {:alias [:c]} :gemini {:alias [:g]}}
   :workflows {:wf {:doc "demo"
                    :stdin-cmd ["git" "diff"]
                    :steps [{:id :a :cmd ["true"]}
                            {:id :b :provider :claude :depends-on [:a]}]}}})

(deftest dryrun-chain-parses-flags-not-as-prompts
  (let [out (dryrun/render dcfg "chain" ["-p" "g" "P1" "P2"] {})]
    (is (re-find #"1\. " out))
    (is (re-find #"2\. " out))
    (is (not (re-find #"3\. " out)) "exactly two steps, not four")
    (is (not (re-find #"\"-p\"|prompt.*-p" out)))
    (is (re-find #"gemini" out))))

(deftest dryrun-workflow-and-oneshot
  (let [w (dryrun/render dcfg "wf" [] {})]
    (is (re-find #"workflow wf" w))
    (is (re-find #"stdin" w))
    (is (re-find #"⟵ \[:a\]" w)))
  (let [o (dryrun/render dcfg "hello world" [] {})]
    (is (re-find #"one-shot" o))))

;; ── mermaid ─────────────────────────────────────────────────────────────────
(deftest mermaid-linear
  (let [m (mermaid/workflow->mermaid "lin" {:steps [{:provider :c}
                                                    {:provider :g}]})]
    (is (re-find #"flowchart TD" m))
    (is (re-find #"Start --> n0" m))
    (is (re-find #"--> End" m))))

(deftest mermaid-dag-edges
  (let [m (mermaid/workflow->mermaid
           "d" {:steps [{:id :a :provider :c}
                        {:id :b :provider :g :depends-on []}
                        {:id :j :provider :c :depends-on [:a :b]}]})]
    (is (re-find #"n0_a --> n2_j" m))
    (is (re-find #"n1_b --> n2_j" m))))

(deftest mermaid-unresolved-dep-does-not-throw
  (let [m (mermaid/workflow->mermaid
           "x" {:steps [{:id :a :provider :c :depends-on [:ghost]}]})]
    (is (re-find #"UNRESOLVED_ghost" m))))

;; ── eval helpers ────────────────────────────────────────────────────────────
(def jaccard #'cw.eval/jaccard)
(def stats   #'cw.eval/stats)

(deftest eval-jaccard
  (is (= 1.0 (jaccard "a b c" "c b a")))
  (is (= 0.0 (jaccard "a b" "c d")))
  (is (= 1.0 (jaccard "" ""))))

(deftest eval-stats
  (let [s (stats [2.0 4.0 6.0])]
    (is (= 4.0 (:mean s)))
    (is (= 2.0 (:min s)))
    (is (= 6.0 (:max s))))
  (is (nil? (stats []))))

;; ── main/step-opts ──────────────────────────────────────────────────────────
(deftest step-opts-coercion
  (is (= {:model "m" :no-fallback true :timeout-ms 30000}
         (main/step-opts {:model "m" :no-fallback true :timeout "30"})))
  (is (= {:timeout-ms 5000} (main/step-opts {:timeout 5})))
  (is (= {} (main/step-opts {}))))

;; ── log ─────────────────────────────────────────────────────────────────────
(def ^:dynamic *cfg* nil)

(use-fixtures :each
  (fn [t]
    (let [d (java.io.File/createTempFile "cw-log" "")]
      (.delete d) (.mkdirs d)
      (binding [*cfg* {:log-dir (.getPath d)}]
        (t)))))

(deftest log-roundtrip-list-load
  (doseq [i (range 3)]
    (log/write-run! *cfg*
                    (r/chain-result {:ok? true :run-id (str "rid" i)
                                     :final-text (str "t" i)
                                     :total-cost-usd 0.01})
                    ["chain"])
    (Thread/sleep 5))
  (let [all (log/list-runs *cfg*)
        two (log/list-runs *cfg* :limit 2)]
    (is (= 3 (count all)))
    (is (= 2 (count two)))
    (is (= "rid2" (:run-id (first all))) "newest first"))
  (let [m (log/load-run *cfg* "rid1")]
    (is (= "t1" (get-in m [:chain-result :final-text]))))
  (is (nil? (log/load-run *cfg* "nope"))))

(deftest log-skips-corrupt-edn
  (log/write-run! *cfg* (r/chain-result {:ok? true :run-id "good"}) ["x"])
  (let [bad (io/file (:log-dir *cfg*) "2026-01-01")]
    (.mkdirs bad)
    (spit (io/file bad "broken.edn") "{not valid edn"))
  (is (= 1 (count (log/list-runs *cfg*))) "corrupt file skipped, good kept"))
