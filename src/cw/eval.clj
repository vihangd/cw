(ns cw.eval
  "Runs a workflow N times and reports output/cost/duration variance.
   Similarity is bag-of-words Jaccard (cheap; under-detects semantics, §12)."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cw.workflow :as workflow]
            [cw.result :as r]))

(defn- words [s]
  (set (re-seq #"\w+" (str/lower-case (str s)))))

(defn- jaccard [a b]
  (let [wa (words a) wb (words b)]
    (if (and (empty? wa) (empty? wb)) 1.0
        (double (/ (count (set/intersection wa wb))
                   (max 1 (count (set/union wa wb))))))))

(defn- stats [xs]
  (when (seq xs)
    (let [n (count xs) m (/ (reduce + 0.0 xs) n)
          var (/ (reduce + 0.0 (map #(Math/pow (- % m) 2) xs)) n)]
      {:mean m :min (apply min xs) :max (apply max xs)
       :stdev (Math/sqrt var)})))

(defn run [config workflow-name opts]
  (when-not workflow-name
    (throw (ex-info "usage: cw eval <workflow> --runs N" {})))
  (let [runs (or (:runs opts) 5)
        fixture (when (:fixture opts) (slurp (:fixture opts)))
        crs (vec (for [_ (range runs)]
                   (workflow/run-workflow config workflow-name []
                                          (cond-> opts
                                            fixture (assoc :stdin fixture)))))
        texts (mapv :final-text crs)
        costs (vec (keep :total-cost-usd crs))
        durs  (mapv (comp double #(or % 0) :total-duration-ms) crs)
        uniq  (count (distinct texts))
        sim   (vec (for [a texts] (vec (for [b texts] (jaccard a b)))))
        cs    (stats costs) ds (stats durs)
        report
        (str/join
         "\n"
         (concat
          [(format "workflow: %s   runs: %d   unique-outputs: %d/%d"
                   workflow-name runs uniq runs)]
          (when cs [(format "cost   mean=$%.4f min=$%.4f max=$%.4f stdev=$%.4f"
                            (:mean cs) (:min cs) (:max cs) (:stdev cs))])
          (when ds [(format "dur    mean=%.0fms min=%.0fms max=%.0fms"
                            (:mean ds) (:min ds) (:max ds))])
          ["similarity matrix:"]
          (for [row sim]
            (str "  " (str/join " " (map #(format "%.2f" %) row))))
          ["outputs:"]
          (map-indexed (fn [i t]
                         (format "  [%d] %s" (inc i)
                                 (let [s (str/trim (str t))]
                                   (subs s 0 (min 80 (count s))))))
                       texts)))]
    (r/result {:provider :cw :ok? (every? :ok? crs) :text report
               :cost-usd (when (seq costs) (reduce + 0.0 costs))
               :raw {:texts texts :similarity sim}})))
