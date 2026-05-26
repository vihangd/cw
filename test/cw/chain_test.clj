(ns cw.chain-test
  (:require [clojure.test :refer [deftest is testing]]
            [cw.chain :as chain]))

(deftest substitute-all-vars
  (is (= "S P a1 X"
         (chain/substitute "{{stdin}} {{prev}} {{arg-1}} {{step-foo}}"
                           {:stdin "S" :prev "P" :args ["a1"]
                            :step-outputs {:foo "X"}})))
  (testing "prev falls back to stdin when nil"
    (is (= "S S" (chain/substitute "{{stdin}} {{prev}}"
                                   {:stdin "S" :prev nil}))))
  (testing "missing arg / step → empty string"
    (is (= "[]" (chain/substitute "[{{arg-9}}{{step-nope}}]" {:args []})))))

(def cfg {:default-provider :claude :providers {} :workflows {}})

(deftest linear-shell-chain
  (let [cr (chain/run-chain
            cfg {:steps [{:cmd ["printf" "one"]}
                         {:cmd ["printf" "got-{{prev}}"]}]
                 :stdin nil :args []})]
    (is (:ok? cr))
    (is (= "got-one" (:final-text cr)))
    (is (= 2 (count (:results cr))))))

(deftest linear-stop-on-failure
  (let [cr (chain/run-chain
            cfg {:steps [{:cmd ["false"]}
                         {:cmd ["printf" "unreached"]}]
                 :stdin nil :args []})]
    (is (not (:ok? cr)))
    (is (= 1 (count (:results cr))))
    (is (= :exec (get-in cr [:error :type])))))

(deftest dag-waves-and-step-refs
  (let [cr (chain/run-chain
            cfg {:steps [{:id :a :cmd ["printf" "A"]}
                         {:id :b :depends-on [] :cmd ["printf" "B"]}
                         {:id :j :depends-on [:a :b]
                          :cmd ["printf" "{{step-a}}{{step-b}}"]}]
                 :stdin nil :args []})]
    (is (:ok? cr))
    (is (= "AB" (:final-text cr)))))

(deftest dag-cycle-detected
  (is (thrown? clojure.lang.ExceptionInfo
               (chain/run-chain
                cfg {:steps [{:id :a :depends-on [:b] :cmd ["true"]}
                             {:id :b :depends-on [:a] :cmd ["true"]}]
                     :stdin nil :args []}))))

(deftest cost-cap-aborts
  ;; shell steps cost 0; force cap negative so the first post-step check trips.
  (let [cr (chain/run-chain
            cfg {:steps [{:cmd ["printf" "x"]} {:cmd ["printf" "y"]}]
                 :stdin nil :args [] :max-cost-usd -1.0})]
    (is (not (:ok? cr)))
    (is (= :cost-cap (get-in cr [:error :type])))))
