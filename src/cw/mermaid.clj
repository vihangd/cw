(ns cw.mermaid
  "workflow → Mermaid flowchart text (paste into any Markdown viewer)."
  (:require [clojure.string :as str]
            [cw.config :as config]
            [cw.result :as r]))

(defn- node-id [i step]
  (str "n" i "_" (some-> (or (:id step) (keyword (str "s" i))) name)))

(defn workflow->mermaid [wname workflow]
  (let [steps   (vec (:steps workflow))
        ids     (vec (map-indexed node-id steps))
        id->idx (into {} (keep-indexed (fn [i s] (when (:id s) [(:id s) i])) steps))
        idx-of  (fn [d] (id->idx (keyword d)))
        label   (fn [i s]
                  (str (ids i) "[\""
                       (or (some-> (:id s) name)
                           (some-> (:provider s) name)
                           "shell")
                       (when (:cmd s) (str " " (first (:cmd s))))
                       (when (contains? s :gate) " (gate)")
                       "\"]"))
        edge    (fn [a b] (str "  " a " --> " b))
        ;; structural edges: for each step, the edges feeding INTO it
        in-edges
        (reduce-kv
         (fn [acc i s]
           (let [deps (:depends-on s)]
             (cond
               (seq deps)
               (into acc (map (fn [d]
                                (if-let [j (idx-of d)]
                                  (edge (ids j) (ids i))
                                  (edge (str "UNRESOLVED_" (name d)) (ids i))))
                              deps))
               (and (contains? s :depends-on) (empty? deps))
               (conj acc (edge "Start" (ids i)))
               (zero? i) (conj acc (edge "Start" (ids i)))
               :else     (conj acc (edge (ids (dec i)) (ids i))))))
         []
         steps)
        ;; terminal edges: steps no other step depends on flow to End
        depended (set (mapcat #(keep idx-of (:depends-on %)) steps))
        out-edges (for [i (range (count steps)) :when (not (depended i))]
                    (edge (ids i) "End"))]
    (str "flowchart TD\n"
         "  %% " wname "\n"
         (str/join "\n" (map-indexed label steps)) "\n"
         (str/join "\n" (distinct (concat in-edges out-edges))))))

(defn run [config tail opts]
  (let [text
        (if (:all opts)
          (str/join "\n\n"
                    (for [[k w] (:workflows config)]
                      (workflow->mermaid (name k) w)))
          (let [name (first tail)
                w (config/resolve-workflow config name)]
            (when-not w (throw (ex-info (str "Unknown workflow: " name) {})))
            (workflow->mermaid name w)))]
    (r/result {:provider :cw :ok? true :text text})))
