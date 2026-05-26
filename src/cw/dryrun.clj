(ns cw.dryrun
  "Renders the plan a command WOULD execute, without running it."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [cw.config :as config]
            [cw.chain :as chain]))

(defn- step-line [i step]
  (let [tag (cond (contains? step :gate)
                  (str "gate "
                       (pr-str (if (string? (:gate step)) (:gate step) "Continue?")))
                  (:cmd step) (str "shell " (pr-str (:cmd step)))
                  :else (str (name (or (:provider step) :?))
                             (when (:model step) (str " (" (:model step) ")"))
                             (when (:depends-on step)
                               (str " ⟵ " (pr-str (:depends-on step))))))]
    (format "  %d. [%s] %s"
            (inc i)
            (or (some-> (:id step) name) "-")
            tag)))

(defn- render-steps [title steps]
  (str title "\n"
       (str/join "\n" (map-indexed step-line steps))))

(defn render
  "config sub tail opts → string plan (or nil if not a dry-runnable command)."
  [config sub tail opts]
  (cond
    (= sub "chain")
    (render-steps "dry-run: ad-hoc chain"
                  (if (:steps-file opts)
                    (:steps (edn/read-string (slurp (:steps-file opts))))
                    (chain/parse-chain-steps
                     config tail
                     (:key (config/resolve-provider config (:provider opts)))
                     {})))

    (config/resolve-workflow config sub)
    (let [w (config/resolve-workflow config sub)]
      (str "dry-run: workflow " sub
           (when (:doc w) (str " — " (:doc w))) "\n"
           (when (:stdin-cmd w)
             (str "  stdin ← " (pr-str (:stdin-cmd w)) "\n"))
           (render-steps "  steps:" (:steps w))))

    sub
    (render-steps "dry-run: one-shot"
                  [{:provider (or (:provider opts) (:default-provider config))
                    :model (:model opts)
                    :prompt (str sub)}])

    :else nil))
