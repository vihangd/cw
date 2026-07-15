(ns cw.config
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn expand-home
  "Expands a leading ~ to the user's home directory."
  [path]
  (when path
    (if (str/starts-with? path "~")
      (str (System/getProperty "user.home") (subs path 1))
      path)))

(defn config-path []
  (or (System/getenv "CW_CONFIG")
      (str (System/getProperty "user.home") "/.config/cw/config.edn")))

(defn- read-edn [src]
  (when src
    (with-open [r (java.io.PushbackReader. (io/reader src))]
      (edn/read r))))

(defn- read-defaults []
  (read-edn (io/resource "cw/default-config.edn")))

(defn- read-edn-warn [f label]
  (try (read-edn f)
       (catch Exception e
         (binding [*out* *err*]
           (println (str "cw warn: " label ": " (.getMessage e))))
         nil)))

(defn- read-user []
  (let [f (io/file (config-path))]
    (when (.exists f) (read-edn-warn f (str "user config " (config-path))))))

(defn- read-project []
  (let [f (io/file ".cw.edn")]
    (when (.exists f) (read-edn-warn f "project config .cw.edn"))))

(defn deep-merge
  "Recursively merges maps. Non-map values from the right win. Used to layer
   defaults ⊕ user ⊕ project. Vectors are replaced, not concatenated."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (nil? b) a
    :else b))

(defn load-config
  "Returns merged config: defaults ⊕ user (~/.config/cw/config.edn) ⊕
   project (./.cw.edn if present)."
  []
  (-> (read-defaults)
      (deep-merge (read-user))
      (deep-merge (read-project))))

(defn- file-prompt-paths
  "Every {:file p} path referenced by :roles/:skills/:fragments in cfg."
  [cfg]
  (->> [:roles :skills :fragments]
       (mapcat #(vals (get cfg %)))
       (keep #(when (and (map? %) (:file %)) (:file %)))
       distinct))

(defn write-starter-config!
  "Writes the baked-in defaults to the user config path and scaffolds the
   referenced prompts/*.md alongside it (no overwrite of existing files)."
  []
  (let [path (config-path)
        f    (io/file path)]
    (if (.exists f)
      {:written false :path path :reason :exists}
      (let [raw (slurp (io/resource "cw/default-config.edn"))]
        (io/make-parents f)
        (spit f raw)
        (let [dir     (-> f .getAbsoluteFile .getParentFile)
              copied  (for [p (file-prompt-paths (edn/read-string raw))
                            :let [src (io/resource (str "cw/" p))
                                  dst (io/file dir p)]
                            :when (and src (not (.exists dst)))]
                        (do (io/make-parents dst) (spit dst (slurp src)) p))]
          {:written true :path path :prompts (vec copied)})))))

(defn- alias->key
  "Builds {alias-keyword provider-key} from every provider's :alias vector."
  [config]
  (reduce-kv (fn [m k v]
               (reduce #(assoc %1 %2 k) m (:alias v)))
             {}
             (:providers config)))

(defn resolve-provider
  "arg can be nil (use default), a keyword, or a string (provider key or alias).
   Returns the provider map with :key assoc'd, or throws ex-info."
  [config arg]
  (let [providers (:providers config)
        kw        (cond
                    (nil? arg) (:default-provider config)
                    (keyword? arg) arg
                    :else (keyword arg))
        resolved  (or (when (contains? providers kw) kw)
                      (get (alias->key config) kw))]
    (if-let [p (get providers resolved)]
      (assoc p :key resolved)
      (throw (ex-info (str "Unknown provider: " (name kw))
                      {:provider arg
                       :known (vec (concat (keys providers)
                                           (keys (alias->key config))))})))))

;; ── shared prompt/phase library expansion (load-time, no runtime engine) ─────

(defn- config-dir []
  (-> (io/file (config-path)) .getAbsoluteFile .getParent))

(defn prompt-value
  "A :roles/:skills/:fragments value is an inline string or {:file path}.
   File lookup order: absolute/~ path · relative to the config dir ·
   bundled classpath resource cw/<path>. Returns the string, or nil."
  [v]
  (cond
    (string? v) v
    (and (map? v) (:file v))
    (let [p   (:file v)
          abs (io/file (expand-home p))
          ;; only build the config-dir-relative path for a relative :file —
          ;; passing an absolute child to a parent path throws.
          rel (when-not (fs/absolute? abs) (io/file (config-dir) p))]
      (cond
        (fs/regular-file? abs)             (slurp abs)
        (and rel (fs/regular-file? rel))   (slurp rel)
        :else (when-let [r (io/resource (str "cw/" p))] (slurp r))))
    :else nil))

(defn- expand-fragments
  "Replaces {{fragment:NAME}} in s with config :fragments values. Load-time.
   Loops up to 10 levels so a fragment may itself reference other fragments;
   the depth bound prevents pathological cycles. Distinct from the runtime
   {{stdin}}/{{prev}}/{{step-*}} substitution in chain.clj."
  [config s]
  (when s
    (loop [s s, depth 0]
      (if (or (>= depth 10) (not (re-find #"\{\{fragment:" s)))
        s
        (recur (str/replace s #"\{\{fragment:([A-Za-z0-9_-]+)\}\}"
                            (fn [[_ n]]
                              (or (prompt-value
                                   (get-in config [:fragments (keyword n)]))
                                  "")))
               (inc depth))))))

(defn expand-step
  "Composes :role + :skill + :prompt (then {{fragment:…}}) into the final
   :prompt, and applies :tier → :provider/:model (explicit step keys win).
   :cmd / :gate steps keep their shape (fragments still expand in prompts).
   Plain :prompt steps with no :role/:skill/:tier are unchanged."
  [config step]
  (if (or (:cmd step) (contains? step :gate))
    (cond-> step
      (string? (:gate step)) (assoc :gate (expand-fragments config (:gate step))))
    (let [role  (prompt-value (get-in config [:roles (:role step)]))
          skill (prompt-value (get-in config [:skills (:skill step)]))
          base  (:prompt step)
          parts (remove (fn [x] (or (nil? x) (str/blank? x))) [role skill base])
          tier  (get-in config [:tiers (:tier step)])]
      (-> step
          (assoc :prompt (expand-fragments config (str/join "\n\n" parts)))
          (cond-> (and tier (not (:provider step)))
            (assoc :provider (:provider tier)))
          (cond-> (and tier (not (:model step)))
            (assoc :model (:model tier)))
          (dissoc :role :skill :tier)))))

;; "__" (not "/") so prefixed ids still match chain's {{step-[A-Za-z0-9_-]+}}.
(defn- prefix-id [pfx id] (keyword (str (name pfx) "__" (name id))))

(defn- rewrite-step-refs
  "Rewrites {{step-<bare>}} → {{step-<prefixed>}} in a string for every id in
   `remap`, so phase-internal references survive id-prefixing."
  [remap s]
  (if (string? s)
    (reduce (fn [acc [from to]]
              (str/replace acc (str "{{step-" (name from) "}}")
                           (str "{{step-" (name to) "}}")))
            s remap)
    s))

(defn- apply-step-rewrite [rw step]
  (cond-> step
    (:prompt step)        (update :prompt rw)
    (string? (:gate step)) (update :gate rw)
    (:cmd step)           (update :cmd #(mapv rw %))))

(defn expand-phases
  "Splices {:use :name} steps with the named phase's steps. Ids and
   :depends-on are prefixed (name__id) for collision safety. Refs of the
   form {{step-<bare>}} in :prompt/:gate/:cmd are rewritten to the prefixed
   form in BOTH the spliced phase steps AND in workflow-level steps that
   follow — so e.g. a workflow step `:prompt \"see {{step-commit}}\"` after
   `{:use :git-ship}` becomes `{{step-git-ship__commit}}`."
  [config steps]
  ;; Pass 1: splice phases and accumulate the union remap.
  (let [{:keys [spliced remap]}
        (reduce
         (fn [{:keys [spliced remap]} step]
           (if-let [pname (:use step)]
             (let [psteps     (get-in config [:phases pname :steps])
                   new-pairs  (into {} (for [s psteps :when (:id s)]
                                         [(:id s) (prefix-id pname (:id s))]))
                   rw         (partial rewrite-step-refs new-pairs)
                   p-steps    (for [s psteps]
                                (cond-> s
                                  (:id s)         (assoc :id (prefix-id pname (:id s)))
                                  (:depends-on s) (assoc :depends-on
                                                         (mapv #(prefix-id pname %) (:depends-on s)))
                                  true            (->> (apply-step-rewrite rw))))]
               {:spliced (into spliced p-steps)
                :remap   (merge remap new-pairs)})
             {:spliced (conj spliced step) :remap remap}))
         {:spliced [] :remap {}}
         steps)]
    ;; Pass 2: rewrite refs in workflow-level steps using the union remap.
    ;; (Re-applying to phase steps is a no-op — their refs are already prefixed.)
    (mapv #(apply-step-rewrite (partial rewrite-step-refs remap) %) spliced)))

(defn expand-workflow
  "Phase-splice, then per-step prompt/tier composition. Pure load-time."
  [config w]
  (update w :steps
          (fn [steps]
            (mapv #(expand-step config %) (expand-phases config steps)))))

(defn resolve-workflow
  "Returns the expanded workflow map (with :name assoc'd) or nil if not found."
  [config name]
  (when name
    (let [kw (keyword name)]
      (when-let [w (get-in config [:workflows kw])]
        (expand-workflow config (assoc w :name kw))))))

(defn- frag-refs [s]
  (when (string? s)
    (map (comp keyword second) (re-seq #"\{\{fragment:([A-Za-z0-9_-]+)\}\}" s))))

(defn- step-errors
  "Validation shared by workflow steps and phase steps. `where` for messages."
  [config where step]
  (let [llm? (some step [:provider :tier :role :skill])]
    (remove
     nil?
     [(when (and (not (:cmd step)) (not (contains? step :gate))
                 (not llm?) (not (:use step)))
        (str where ": step has none of :provider/:tier/:role/:skill, :cmd, "
             ":gate, :use"))
      (when (and llm?
                 (not (:cmd step)) (not (contains? step :gate)) (not (:use step))
                 (not (:role step)) (not (:skill step)) (not (:prompt step)))
        (str where ": LLM step has :provider/:tier but no :role/:skill/:prompt"
             " — would run with a blank prompt"))
      (when (and (or (:cmd step) (contains? step :gate))
                 (some step [:role :skill :tier]))
        (str where ": :role/:skill/:tier not allowed on a :cmd/:gate step"))
      (when (and (:role step) (not (get-in config [:roles (:role step)])))
        (str where ": unknown :role " (pr-str (:role step))))
      (when (and (:skill step) (not (get-in config [:skills (:skill step)])))
        (str where ": unknown :skill " (pr-str (:skill step))))
      (when (and (:tier step) (not (get-in config [:tiers (:tier step)])))
        (str where ": unknown :tier " (pr-str (:tier step))))
      (when (and (:use step) (not (get-in config [:phases (:use step)])))
        (str where ": unknown :use phase " (pr-str (:use step))))
      (let [bad (remove #(get-in config [:fragments %])
                        (concat (frag-refs (:prompt step))
                                (frag-refs (when (string? (:gate step))
                                             (:gate step)))))]
        (when (seq bad)
          (str where ": unknown {{fragment:…}} " (pr-str (vec bad)))))])))

(defn validate
  "Structural validation. Returns the config if ok, else throws ex-info with
   :errors. Covers: step kind (incl. :role/:skill/:tier/:use); role/skill/
   tier/phase/fragment references resolve; :file prompt targets load; phases
   don't nest (⇒ acyclic); :depends-on/:id within a workflow; pricing keys."
  [config]
  (let [errors
        (concat
         ;; workflow steps
         (for [[wname w] (:workflows config)
               step (:steps w)
               e (step-errors config (str "workflow " wname) step)]
           e)
         ;; intra-workflow :depends-on / duplicate :id (authored steps)
         (for [[wname w] (:workflows config)
               :let [ids (set (keep :id (:steps w)))]
               step (:steps w)
               :when (some #(and (not= [] (:depends-on step)) (not (ids %)))
                           (:depends-on step))]
           (str "workflow " wname ": unknown :depends-on id in "
                (pr-str (:depends-on step))))
         (for [[wname w] (:workflows config)
               :let [dups (->> (keep :id (:steps w)) frequencies
                               (filter #(> (val %) 1)) (map key))]
               :when (seq dups)]
           (str "workflow " wname ": duplicate step :id " (pr-str (vec dups))))
         ;; phase steps (same rules) + phases must not nest
         (for [[pname p] (:phases config)
               step (:steps p)
               e (concat (step-errors config (str "phase " pname) step)
                         (when (:use step)
                           [(str "phase " pname ": phases may not :use other "
                                 "phases (keeps expansion acyclic)")]))]
           e)
         ;; :file prompt targets must load
         (for [section [:roles :skills :fragments]
               [k v] (get config section)
               :when (and (map? v) (:file v) (nil? (prompt-value v)))]
           (str (name section) " " k ": :file " (pr-str (:file v))
                " not found (config dir or bundled cw/ resource)"))
         ;; pricing keys
         (for [[pk pv] (:providers config)
               :let [pkey (:pricing-key pv)]
               :when (and pkey (not (contains? (:pricing config) pkey)))]
           (str "provider " pk ": pricing-key " (pr-str pkey)
                " has no :pricing entry")))]
    (if (seq errors)
      (throw (ex-info "Invalid config" {:errors (vec errors)}))
      config)))
