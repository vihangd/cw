(ns cw.compare
  "Same prompt → every configured provider, in parallel (gap #4). Fallback is
   disabled so you see each provider's own answer."
  (:require [clojure.string :as str]
            [cw.provider :as provider]
            [cw.result :as r]))

(defn run [config prompt opts]
  (when-not prompt
    (throw (ex-info "usage: cw compare PROMPT" {})))
  (let [keys (if-let [ps (:providers opts)]
               (map keyword (str/split ps #",\s*"))
               (keys (:providers config)))
        results (->> keys
                     (pmap (fn [k]
                             [k (try
                                  (provider/run config
                                                {:provider k :prompt prompt
                                                 :no-fallback true
                                                 :model (:model opts)})
                                  (catch Throwable e
                                    (r/result {:provider k :ok? false
                                               :error {:type :exec
                                                       :message (str (.getMessage e))}})))]))
                     (into {}))
        block (str/join "\n\n"
                        (for [k keys
                              :let [res (results k)]]
                          (format "── %s%s ──\n%s"
                                  (name k)
                                  (if (:ok? res) "" " (FAILED)")
                                  (if (:ok? res)
                                    (str/trim (str (:text res)))
                                    (get-in res [:error :message])))))
        total (reduce + 0.0 (keep (comp :cost-usd val) results))]
    (r/result {:provider :cw :text block :ok? true
               :cost-usd (when (pos? total) total)
               :raw results})))
