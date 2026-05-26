(ns cw.workflow-test
  (:require [clojure.test :refer [deftest is testing]]
            [cw.config :as config]
            [cw.workflow :as workflow]))

(deftest stdin-precedence
  (testing "piped wins over :stdin-cmd"
    (is (= "PIPED"
           (workflow/resolve-stdin {:stdin-cmd ["printf" "FROMCMD"]} "PIPED"))))
  (testing ":stdin-cmd used when nothing piped"
    (is (= "FROMCMD"
           (workflow/resolve-stdin {:stdin-cmd ["printf" "FROMCMD"]} nil))))
  (testing "nil when neither"
    (is (nil? (workflow/resolve-stdin {} nil)))))

(def cfg
  {:default-provider :claude
   :providers {:claude {:cmd "claude" :alias [:c]}}
   :workflows
   {:echo {:doc "shell-only hermetic workflow"
           :steps [{:id :a :cmd ["printf" "hello {{arg-1}}"]}
                   {:id :b :depends-on [:a]
                    :cmd ["printf" "<{{step-a}}>"]}]}}})

(deftest resolvers
  (is (= :claude (:key (config/resolve-provider cfg "c"))))
  (is (= :claude (:key (config/resolve-provider cfg nil))))
  (is (= :echo (:name (config/resolve-workflow cfg "echo"))))
  (is (nil? (config/resolve-workflow cfg "missing"))))

(deftest run-workflow-with-args
  (let [cr (workflow/run-workflow cfg "echo" ["world"] {:stdin ""})]
    (is (:ok? cr))
    (is (= "<hello world>" (:final-text cr)))))

(deftest unknown-workflow-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (workflow/run-workflow cfg "nope" [] {:stdin ""}))))
