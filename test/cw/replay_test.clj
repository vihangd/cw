(ns cw.replay-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cw.chain :as chain]
            [cw.log :as log]
            [cw.replay :as replay]))

(def ^:dynamic *cfg* nil)

(use-fixtures :each
  (fn [t]
    (let [d (java.io.File/createTempFile "cw-runs" "")]
      (.delete d) (.mkdirs d)
      (binding [*cfg* {:log-dir (.getPath d) :default-provider :claude
                       :providers {} :workflows {}}]
        (t)))))

(def steps [{:id :a :cmd ["printf" "alpha"]}
            {:id :b :cmd ["printf" "got-{{prev}}"]}])

(deftest roundtrip-run-log-load
  (let [cr (chain/run-chain *cfg* {:steps steps :stdin nil :args []})
        _  (log/write-run! *cfg* cr ["chain" "a" "b"])
        m  (log/load-run *cfg* (:run-id cr))]
    (is (= "got-alpha" (:final-text cr)))
    (is (= (:run-id cr) (:run-id m)))
    (is (= "got-alpha" (get-in m [:chain-result :final-text])))))

(deftest replay-exact
  (let [cr (chain/run-chain *cfg* {:steps steps :stdin nil :args []})
        _  (log/write-run! *cfg* cr ["chain"])
        rp (replay/run *cfg* (:run-id cr) {})]
    (is (:ok? rp))
    (is (= "got-alpha" (:final-text rp)))
    (is (not= (:run-id cr) (:run-id rp)) "replay gets a fresh run-id")))

(deftest replay-step-slice-seeds-prev
  (let [cr (chain/run-chain *cfg* {:steps steps :stdin nil :args []})
        _  (log/write-run! *cfg* cr ["chain"])
        ;; re-run only step 2; {{prev}} must come from logged step 1
        rp (replay/run *cfg* (:run-id cr) {:step "2"})]
    (is (:ok? rp))
    (is (= 1 (count (:results rp))))
    (is (= "got-alpha" (:final-text rp)))))

(deftest replay-unknown-run-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (replay/run *cfg* "no-such-id" {}))))
