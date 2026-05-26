(ns cw.gate-test
  "Gate steps: pure decision parsing + chain behaviour. Hermetic — the test
   env has no /dev/tty, which exercises the fail-closed path directly."
  (:require [clojure.test :refer [deftest is testing]]
            [cw.chain :as chain]))

(def cfg {:default-provider :claude :providers {} :workflows {}})

(deftest gate-decision-pure
  (testing "affirmative answers approve"
    (doseq [a ["y" "yes" "Y" "YES" "  yes  " "\tY\n"]]
      (is (= :approved (chain/gate-decision a)) a)))
  (testing "everything else (default/EOF/no) rejects — fail closed"
    (doseq [a ["" "n" "no" "nope" "sure" "1" nil "approve"]]
      (is (= :rejected (chain/gate-decision a)) (pr-str a)))))

(deftest gate-step-predicate
  (is (chain/gate-step? {:gate "ok?"}))
  (is (chain/gate-step? {:gate true}))
  (is (not (chain/gate-step? {:cmd ["true"]})))
  (is (not (chain/gate-step? {:provider :claude}))))

(deftest gate-with-yes-bypass-continues
  (let [cr (chain/run-chain
            cfg {:steps [{:id :a :cmd ["printf" "one"]}
                         {:id :g :gate "post {{prev}}?"}
                         {:id :b :cmd ["printf" "after-{{prev}}"]}]
                 :stdin nil :args [] :yes true})]
    (is (:ok? cr))
    (is (= 3 (count (:results cr))))
    (is (= "approved (--yes)" (:text (second (:results cr))))
        "gate decision is still recorded")
    (is (= "after-one" (:final-text cr))
        "gate is transparent: {{prev}} for :b is :a's output, not the gate's")))

(deftest gate-without-tty-or-yes-fails-closed
  (let [cr (chain/run-chain
            cfg {:steps [{:id :a :cmd ["printf" "one"]}
                         {:id :g :gate "proceed?"}
                         {:id :b :cmd ["printf" "SHOULD-NOT-RUN"]}]
                 :stdin nil :args [] :yes false})]
    (is (not (:ok? cr)))
    (is (= :gate-rejected (get-in cr [:error :type])))
    (is (= :g (:failed-at cr)))
    (is (= 2 (count (:results cr))) "stops at the gate; :b never runs")
    (is (not (some #(= "SHOULD-NOT-RUN" (:text %)) (:results cr))))))

(deftest gate-works-in-dag-position
  ;; :depends-on makes this a DAG; the gate is its own wave between a and b.
  (let [cr (chain/run-chain
            cfg {:steps [{:id :a :cmd ["printf" "x"]}
                         {:id :g :gate "ok?" :depends-on [:a]}
                         {:id :b :cmd ["printf" "done"] :depends-on [:g]}]
                 :stdin nil :args [] :yes true})]
    (is (:ok? cr))
    (is (= "done" (:final-text cr)))))
