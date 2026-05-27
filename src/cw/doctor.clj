(ns cw.doctor
  "Environment diagnostics: provider CLIs, RTK/lean-ctx, config, state, logs."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [cw.config :as config]
            [cw.result :as r]))

(defn- which [bin] (some-> (fs/which bin) str))

(defn- glyph [status] (case status :ok "✓" :warn "⚠" :fail "✗"))

(defn- check [category status message & [hint]]
  {:category category :status status :message message :hint hint})

(defn- check-config [config]
  (try (config/validate config)
       (check "config" :ok
              (format "%s valid (%d providers, %d workflows)"
                      (config/config-path)
                      (count (:providers config)) (count (:workflows config))))
       (catch Exception e
         (check "config" :fail (str "invalid: " (.getMessage e))))))

(defn- check-provider [config pk]
  (let [cmd     (get-in config [:providers pk :cmd])
        path    (which cmd)
        default (= pk (:default-provider config))]
    (cond
      path (check "providers" :ok (format "%s found at %s" (name pk) path))
      ;; Missing providers are optional in cw's multi-provider model — they
      ;; warn, not fail. Default provider gets a sharper hint since `cw`
      ;; with no -p will fail until it's installed or default is changed.
      default
      (check "providers" :warn
             (format "%s (%s) not on PATH — this is :default-provider"
                     (name pk) cmd)
             (str "install " (name pk)
                  ", or change :default-provider in your config"))
      :else
      (check "providers" :warn
             (format "%s (%s) not on PATH (optional)" (name pk) cmd)
             (str "install if you want `-p " (name pk) "` to work")))))

(defn- check-rtk []
  (if-let [p (which "rtk")]
    (check "integrations" :ok (str "RTK detected at " p))
    (check "integrations" :warn "RTK not installed"
           "optional token-saving proxy")))

(defn- check-lean-ctx []
  (if-let [p (which "lean-ctx")]
    (check "integrations" :ok (str "lean-ctx detected at " p))
    (check "integrations" :warn "lean-ctx not installed"
           "cargo install lean-ctx")))

(defn- check-state [config]
  (let [f (io/file (config/expand-home (:state-file config)))]
    (if (.exists f)
      (check "state" :ok (str (.getPath f) " present"))
      (check "state" :warn (str (.getPath f) " not yet created")
             "created on first run"))))

(defn- check-logs [config]
  (let [d (io/file (config/expand-home (:log-dir config)))]
    (if (.exists d)
      (let [n (count (filter #(.endsWith (.getName %) ".edn") (file-seq d)))]
        (check "state" :ok (format "%s (%d logged runs)" (.getPath d) n)))
      (check "state" :warn (str (.getPath d) " not yet created")))))

(defn- bare-note [config]
  (let [args (get-in config [:providers :claude :args])]
    (if (some #{"--bare"} args)
      (check "config notes" :warn
             "claude args include --bare → RTK/lean-ctx hooks won't load")
      (check "config notes" :ok
             "claude args omit --bare (RTK/lean-ctx friendly)"))))

(defn run [config]
  (let [checks (concat
                [(check-config config)]
                (map #(check-provider config %) (keys (:providers config)))
                [(check-rtk) (check-lean-ctx)
                 (check-state config) (check-logs config)
                 (bare-note config)])
        lines (for [c checks]
                (str (glyph (:status c)) " [" (:category c) "] " (:message c)
                     (when (:hint c) (str "\n    hint: " (:hint c)))))
        ok?   (not-any? #(= :fail (:status %)) checks)]
    (doseq [l lines] (println l))
    (r/result {:provider :cw :ok? ok? :text ""
               :error (when-not ok?
                        {:type :exec :message "doctor found problems"})})))
