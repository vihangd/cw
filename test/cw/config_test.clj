(ns cw.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [cw.config :as config]))

(def deep-merge #'cw.config/deep-merge)

(deftest deep-merge-semantics
  (is (= {:a {:b 1 :c 2}}
         (deep-merge {:a {:b 1}} {:a {:c 2}})))
  (testing "right scalar wins"
    (is (= {:a 2} (deep-merge {:a 1} {:a 2}))))
  (testing "nil right preserves left (partial user override)"
    (is (= {:a 1} (deep-merge {:a 1} {:a nil}))))
  (testing "vectors replaced, not concatenated"
    (is (= {:x [3]} (deep-merge {:x [1 2]} {:x [3]})))))

(deftest expand-home
  (is (= (str (System/getProperty "user.home") "/x")
         (config/expand-home "~/x")))
  (is (= "/abs" (config/expand-home "/abs")))
  (is (nil? (config/expand-home nil))))

(deftest resolve-provider-alias-and-throw
  (let [cfg {:default-provider :claude
             :providers {:claude {:alias [:c]} :gemini {:alias [:g :gg]}}}]
    (is (= :claude (:key (config/resolve-provider cfg nil))))
    (is (= :claude (:key (config/resolve-provider cfg "c"))))
    (is (= :gemini (:key (config/resolve-provider cfg :gemini))))
    (is (= :gemini (:key (config/resolve-provider cfg "gg"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/resolve-provider cfg "nope")))))

(defn- errs [cfg]
  (try (config/validate cfg) nil
       (catch clojure.lang.ExceptionInfo e (:errors (ex-data e)))))

(deftest validate-error-paths
  (testing "step with no kind at all"
    (is (some #(re-find #"step has none of" %)
              (errs {:workflows {:w {:steps [{:prompt "x"}]}}}))))
  (testing "a :gate step is a valid step kind"
    (is (nil? (errs {:workflows {:w {:steps [{:gate "ok?"}]}}}))))
  (testing "unresolved :depends-on"
    (is (some #(re-find #"unknown :depends-on" %)
              (errs {:workflows {:w {:steps [{:id :a :cmd ["true"]}
                                             {:id :b :provider :c
                                              :depends-on [:missing]}]}}}))))
  (testing "duplicate step :id"
    (is (some #(re-find #"duplicate step :id" %)
              (errs {:workflows {:w {:steps [{:id :a :cmd ["true"]}
                                             {:id :a :cmd ["true"]}]}}}))))
  (testing "pricing-key with no entry"
    (is (some #(re-find #"has no :pricing entry" %)
              (errs {:providers {:p {:pricing-key "ghost"}}
                     :pricing {}}))))
  (testing "valid config returns the config"
    (is (= {:workflows {:w {:steps [{:cmd ["true"]}]}}}
           (config/validate {:workflows {:w {:steps [{:cmd ["true"]}]}}})))))

;; Validate the SHIPPED defaults directly, not load-config — the latter
;; deep-merges the developer's real ~/.config/cw/config.edn, which would make
;; this test fail for environment reasons rather than code.
(def read-defaults #'cw.config/read-defaults)

(deftest default-config-is-valid
  (let [c (read-defaults)]
    (is (config/validate c))
    (is (contains? (:providers c) :claude))
    (is (every? (set (keys (:workflows c)))
                [:commit-msg :pr-review :research :git-ship :security-review])
        "shipped + ported + catalog workflows present")
    (is (contains? (:phases c) :git-ship))
    (is (seq (:roles c)))
    (is (seq (:skills c)))))

;; ── shared library expansion (the two pillars) ──────────────────────────────

(def lib
  {:default-provider :claude
   :providers {:claude {:alias [:c]}}
   :roles    {:critic "PERSONA"}
   :skills   {:rev "TASK {{stdin}}"}
   :fragments {:verdict "EMIT-ONE-TOKEN"}
   :tiers    {:cheap {:provider :gemini :model "g-mini"}}
   :phases   {:ship {:steps [{:id :a :cmd ["echo" "hi"]}
                             {:id :b :depends-on [:a]
                              :role :critic :skill :rev
                              :prompt "uses {{step-a}}"}]}}
   :workflows
   {:plain  {:steps [{:provider :claude :prompt "literal {{stdin}}"}]}
    :compose {:steps [{:id :r :role :critic :skill :rev :tier :cheap
                       :prompt "extra {{fragment:verdict}}"}]}
    :winner  {:steps [{:role :critic :tier :cheap :provider :claude}]}
    :uses    {:steps [{:use :ship} {:id :z :provider :claude :prompt "p"}]}}})

(deftest expand-step-composes-prompt-and-tier
  (let [w (config/resolve-workflow lib "compose")
        s (first (:steps w))]
    (is (= "PERSONA\n\nTASK {{stdin}}\n\nextra EMIT-ONE-TOKEN" (:prompt s))
        "role + skill + prompt joined; {{fragment:…}} expanded; {{stdin}} left for runtime")
    (is (= :gemini (:provider s)) "tier fills provider")
    (is (= "g-mini" (:model s)))
    (is (not (contains? s :role)) "ref keys stripped after expansion")))

(deftest tier-fills-model-but-explicit-model-wins
  (let [c (assoc-in lib [:workflows :w]
                    {:steps [{:tier :cheap :model "override-m" :skill :rev}]})
        s (first (:steps (config/resolve-workflow c "w")))]
    (is (= "override-m" (:model s)) "explicit :model beats tier :model")
    (is (= :gemini (:provider s)) "tier (:cheap → :gemini here) still fills :provider")))

(deftest file-backed-skill-is-slurped-and-composed
  (let [f (java.io.File/createTempFile "cw-skill" ".md")]
    (spit f "FILE-SKILL-BODY {{fragment:verdict}}")
    (let [c (-> lib
                (assoc-in [:skills :fs] {:file (.getAbsolutePath f)})
                (assoc-in [:workflows :w]
                          {:steps [{:role :critic :skill :fs}]}))
          s (first (:steps (config/resolve-workflow c "w")))]
      (is (= "PERSONA\n\nFILE-SKILL-BODY EMIT-ONE-TOKEN" (:prompt s))
          ":file skill body slurped, composed with role, fragment expanded"))
    (.delete f)))

(deftest explicit-step-keys-beat-tier
  (let [s (first (:steps (config/resolve-workflow lib "winner")))]
    (is (= :claude (:provider s)) "explicit :provider wins over tier")))

(deftest plain-prompt-unchanged-backward-compat
  (let [s (first (:steps (config/resolve-workflow lib "plain")))]
    (is (= "literal {{stdin}}" (:prompt s)))
    (is (= :claude (:provider s)))))

(deftest phases-splice-with-id-prefix
  (let [steps (:steps (config/resolve-workflow lib "uses"))]
    (is (= [:ship__a :ship__b :z] (mapv :id steps)))
    (is (= [:ship__a] (:depends-on (second steps))) "intra-phase dep re-prefixed")
    (is (= "PERSONA\n\nTASK {{stdin}}\n\nuses {{step-ship__a}}"
           (:prompt (second steps)))
        "phase step: prompt composed AND {{step-…}} ref re-prefixed")))

(deftest validate-rejects-bad-refs
  (is (some #(re-find #"unknown :role" %)
            (errs (assoc lib :workflows {:w {:steps [{:role :ghost}]}}))))
  (is (some #(re-find #"unknown :skill" %)
            (errs (assoc lib :workflows {:w {:steps [{:skill :ghost}]}}))))
  (is (some #(re-find #"unknown :tier" %)
            (errs (assoc lib :workflows {:w {:steps [{:tier :ghost}]}}))))
  (is (some #(re-find #"unknown :use phase" %)
            (errs (assoc lib :workflows {:w {:steps [{:use :ghost}]}}))))
  (is (some #(re-find #"unknown \{\{fragment" %)
            (errs (assoc lib :workflows
                         {:w {:steps [{:provider :claude
                                       :prompt "{{fragment:nope}}"}]}}))))
  (is (some #(re-find #"not allowed on a :cmd/:gate" %)
            (errs (assoc lib :workflows
                         {:w {:steps [{:cmd ["true"] :role :critic}]}}))))
  (is (some #(re-find #"phases may not :use" %)
            (errs (assoc lib :phases {:p {:steps [{:use :ship}]}}
                         :workflows {:w {:steps [{:use :p}]}})))))

(deftest validate-passes-clean-library
  (is (nil? (errs lib))))

;; ── bug fixes from the 2026-05-20 audit ─────────────────────────────────────

(deftest workflow-level-step-after-phase-rewrites-refs
  ;; Bug 1: previously {{step-a}} in a workflow-level step AFTER {:use :ship}
  ;; was left literal and silently substituted to "" at runtime (because the
  ;; spliced id is :ship__a). expand-phases must rewrite refs in
  ;; workflow-level steps too.
  (let [c (assoc-in lib [:workflows :w]
                    {:steps [{:use :ship}
                             {:id :tail :provider :claude
                              :prompt "ref {{step-a}} and stdin {{stdin}}"}]})
        tail (last (:steps (config/resolve-workflow c "w")))]
    (is (= "ref {{step-ship__a}} and stdin {{stdin}}" (:prompt tail))
        "workflow-level step ref rewritten to the prefixed phase id")))

(deftest validate-rejects-blank-llm-step
  ;; Bug 3: a step with :tier/:provider but no :role/:skill/:prompt expands
  ;; to :prompt "" — silently calls the LLM with nothing. validate must catch.
  (is (some #(re-find #"blank prompt" %)
            (errs (assoc lib :workflows
                         {:w {:steps [{:tier :cheap :fallback [:gemini]}]}}))))
  (is (some #(re-find #"blank prompt" %)
            (errs (assoc lib :workflows
                         {:w {:steps [{:provider :claude}]}}))))
  (testing ":role alone is enough — composes the persona as the prompt"
    (is (nil? (errs (assoc lib :workflows
                           {:w {:steps [{:role :critic :tier :cheap}]}}))))))

(deftest fragments-expand-recursively
  ;; Issue 5: a fragment value may itself reference {{fragment:…}}. The
  ;; loop expands up to 10 levels.
  (let [c (-> lib
              (assoc-in [:fragments :outer] "OUTER+{{fragment:inner}}")
              (assoc-in [:fragments :inner] "INNER")
              (assoc-in [:workflows :w]
                        {:steps [{:provider :claude
                                  :prompt "go {{fragment:outer}}"}]}))
        s (first (:steps (config/resolve-workflow c "w")))]
    (is (= "go OUTER+INNER" (:prompt s))
        "outer fragment expanded, then its embedded inner fragment expanded too")))

(deftest fragments-cycle-bounded-not-infinite
  ;; A fragment that references itself terminates at the depth bound; the
  ;; result is non-empty (no crash, no hang) — we only assert it returns.
  (let [c (-> lib
              (assoc-in [:fragments :loop] "L-{{fragment:loop}}")
              (assoc-in [:workflows :w]
                        {:steps [{:provider :claude
                                  :prompt "{{fragment:loop}}"}]}))
        s (first (:steps (config/resolve-workflow c "w")))]
    (is (string? (:prompt s)))
    (is (re-find #"^L-L-L-" (:prompt s))
        "expanded several levels before the bound stopped it")))

(deftest gate-step-allowed-inside-a-phase
  ;; Gate is a valid step kind anywhere (workflow, phase, ad-hoc steps-file).
  (let [c (-> lib
              (assoc-in [:phases :ship-gated]
                        {:steps [{:id :a :cmd ["echo" "x"]}
                                 {:id :ok :gate "ship?" :depends-on [:a]}]})
              (assoc-in [:workflows :w]
                        {:steps [{:use :ship-gated}]}))]
    (is (nil? (errs c)) "phase + gate validates clean")
    (let [steps (:steps (config/resolve-workflow c "w"))]
      (is (= [:ship-gated__a :ship-gated__ok] (mapv :id steps))
          "gate step gets the same id-prefix treatment"))))

(deftest prompt-value-classpath-fallback
  ;; The bundled adversarial-review.md ships under resources/cw/prompts/.
  ;; prompt-value's lookup order is abs → config-dir-relative → classpath.
  ;; If `cw config init` has scaffolded ~/.config/cw/prompts/adversarial-review.md,
  ;; step 2 would silently win and we'd never test the classpath fallback.
  ;; Stubbing config-dir to a guaranteed-empty path forces step 3.
  (with-redefs [cw.config/config-dir (constantly "/var/empty/cw-test-no-such")]
    (let [s (config/prompt-value {:file "prompts/adversarial-review.md"})]
      (is (string? s))
      (is (re-find #"Verdict|Adversarial|four passes" s)
          "bundled prompt actually loaded via classpath fallback"))))
