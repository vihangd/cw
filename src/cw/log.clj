(ns cw.log
  "Per-run EDN audit log: <log-dir>/<yyyy-mm-dd>/<run-id>.edn. Records are
   deep-converted to plain maps so the files load without custom readers."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [cw.config :as config]
            [cw.result :as r]))

(defn- log-dir [config]
  (io/file (config/expand-home (:log-dir config))))

(defn- ->plain [x]
  (walk/postwalk #(if (record? %) (into {} %) %) x))

(defn- today-str [] (str (java.time.LocalDate/now)))

(defn write-run!
  "Writes one EDN file per run. cli-args is the raw argv for replay context."
  [config chain-result cli-args]
  (let [run-id (:run-id chain-result)
        dir    (io/file (log-dir config) (today-str))
        f      (io/file dir (str run-id ".edn"))
        record {:run-id run-id
                :timestamp (str (java.time.Instant/now))
                :command (vec cli-args)
                :ok? (:ok? chain-result)
                :total-cost-usd (:total-cost-usd chain-result)
                :workflow (when (seq cli-args) (first cli-args))
                :chain-result (->plain chain-result)}]
    (io/make-parents f)
    (spit f (pr-str record))
    f))

(defn- all-run-files [config]
  (let [d (log-dir config)]
    (when (.exists d) (map fs/file (fs/glob d "**/*.edn")))))

(defn list-runs
  "Summary maps, newest first. limit defaults to 20 (gap #9)."
  [config & {:keys [limit] :or {limit 20}}]
  (->> (all-run-files config)
       (keep (fn [f] (try (edn/read-string (slurp f)) (catch Exception _ nil))))
       (sort-by :timestamp)
       reverse
       (take limit)
       vec))

(defn load-run
  "Full logged record (with :chain-result map) for a run-id, or nil."
  [config run-id]
  (some (fn [f]
          (let [m (try (edn/read-string (slurp f)) (catch Exception _ nil))]
            (when (= run-id (:run-id m)) m)))
        (all-run-files config)))

;; ── presentation (called from cw.main runs dispatch) ────────────────────────
(defn print-list [config opts]
  (doseq [run (list-runs config :limit (or (:limit opts) 20))]
    (println (format "%s  %-8s  cost=%s  %s"
                     (:timestamp run)
                     (if (:ok? run) "ok" "FAIL")
                     (if-let [c (:total-cost-usd run)]
                       (format "$%.4f" (double c)) "?")
                     (:run-id run))))
  (r/ok-result))

(defn print-show [config run-id]
  (if-let [m (load-run config run-id)]
    (do (println (pr-str m)) (r/ok-result))
    (r/result {:provider :cw :ok? false
               :error {:type :exec :message (str "no such run: " run-id)}})))
