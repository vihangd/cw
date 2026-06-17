(ns cw.result-test
  (:require [clojure.test :refer [deftest is testing]]
            [cw.result :as r]))

(deftest result-exit-code-legacy
  (testing "flag off → legacy 0/1 on :ok?"
    (is (= 0 (r/result-exit-code {:ok? true :final-text "RESULT: BLOCKED reason=x"} false)))
    (is (= 1 (r/result-exit-code {:ok? false :final-text "RESULT: NOTHING_TO_DO"} false))))
  (testing "failed run is always 1 even with flag on"
    (is (= 1 (r/result-exit-code {:ok? false :final-text "RESULT: NOTHING_TO_DO"} true)))))

(deftest result-exit-code-protocol
  (testing "flag on + ok → RESULT sentinel refines the code"
    (is (= 0 (r/result-exit-code {:ok? true :final-text "did work\nRESULT: DONE items=3"} true)))
    (is (= 0 (r/result-exit-code {:ok? true :final-text "nope\nRESULT: NOTHING_TO_DO"} true)))
    (is (= 2 (r/result-exit-code {:ok? true :final-text "stuck\nRESULT: BLOCKED reason=missing creds"} true))))
  (testing "no sentinel → 0"
    (is (= 0 (r/result-exit-code {:ok? true :final-text "just prose, no result line"} true))))
  (testing "last RESULT line wins; trailing whitespace tolerated"
    (is (= 2 (r/result-exit-code {:ok? true :final-text "RESULT: DONE items=1\n  RESULT: BLOCKED reason=late  "} true))))
  (testing "falls back to :text when no :final-text (Result vs ChainResult)"
    (is (= 0 (r/result-exit-code {:ok? true :text "RESULT: NOTHING_TO_DO"} true)))))
