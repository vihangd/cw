(ns cw.state
  "Persistent per-provider credit/blacklist tracking. Single-user; last-writer
   wins. Writes go to a sibling tempfile then ATOMIC_MOVE over the target so a
   crash mid-write can't corrupt state (gap #7)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cw.config :as config])
  (:import [java.time Instant ZoneOffset]
           [java.time.temporal ChronoUnit]))

(defn- state-file [config]
  (io/file (config/expand-home (:state-file config))))

(defn load-state [config]
  (let [f (state-file config)]
    (if (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ {:providers {}}))
      {:providers {}})))

(defn save-state! [config state]
  (let [f   (state-file config)
        tmp (io/file (str (.getPath f) ".tmp." (System/nanoTime)))]
    (io/make-parents f)
    (spit tmp (pr-str state))
    (fs/move tmp f {:atomic-move true :replace-existing true})
    state))

(defn- now [] (Instant/now))
(defn- iso [^Instant i] (str i))
(defn- parse-iso [s] (when s (Instant/parse s)))

(defn- today-reset-instant
  "The most recent reset boundary for a provider given :reset-hour-utc."
  [provider-cfg]
  (let [hour (or (:reset-hour-utc provider-cfg) 0)
        d    (.atZone (now) ZoneOffset/UTC)
        boundary (-> d (.withHour hour) (.withMinute 0) (.withSecond 0) (.withNano 0))
        boundary (if (.isAfter boundary d) (.minusDays boundary 1) boundary)]
    (.toInstant boundary)))

(defn maybe-reset-daily!
  "Zeroes requests-today if last-reset precedes the current reset boundary.
   Pure: returns the (possibly updated) state."
  [state provider-key provider-cfg]
  (let [path     [:providers provider-key]
        ps       (get-in state path)
        boundary (today-reset-instant provider-cfg)
        last     (parse-iso (:last-reset ps))]
    (if (or (nil? last) (.isBefore last boundary))
      (assoc-in state path (assoc ps :requests-today 0
                                  :last-reset (iso boundary)))
      state)))

(defn can-use?
  "True if provider is not blacklisted and not over its daily request limit."
  [state provider-key provider-cfg]
  (let [st (maybe-reset-daily! state provider-key provider-cfg)
        ps (get-in st [:providers provider-key])
        bl (parse-iso (:blacklisted-until ps))
        lim (:daily-request-limit provider-cfg)]
    (and (or (nil? bl) (.isAfter (now) bl))
         (or (nil? lim) (< (or (:requests-today ps) 0) lim)))))

(defn record-success! [config provider-key]
  (let [pcfg (get-in config [:providers provider-key])
        st   (-> (load-state config)
                 (maybe-reset-daily! provider-key pcfg))
        ps   (get-in st [:providers provider-key])]
    (save-state! config
                 (assoc-in st [:providers provider-key]
                           (assoc ps
                                  :last-used-at (iso (now))
                                  :requests-today (inc (or (:requests-today ps) 0))
                                  :consecutive-errors 0
                                  :blacklisted-until nil)))))

(defn record-failure!
  "Increments consecutive-errors. At/over threshold, blacklists the provider:
   :out-of-credits → until next daily reset (~24h); else → 1 hour."
  [config provider-key error-type]
  (let [threshold (or (:blacklist-threshold config) 3)
        pcfg      (get-in config [:providers provider-key])
        st        (-> (load-state config)
                      (maybe-reset-daily! provider-key pcfg))
        ps        (get-in st [:providers provider-key])
        errs      (inc (or (:consecutive-errors ps) 0))
        bl-until  (when (>= errs threshold)
                    (iso (if (= error-type :out-of-credits)
                           (.plus (now) 24 ChronoUnit/HOURS)
                           (.plus (now) 1 ChronoUnit/HOURS))))]
    (save-state! config
                 (assoc-in st [:providers provider-key]
                           (assoc ps
                                  :last-used-at (iso (now))
                                  :consecutive-errors errs
                                  :blacklisted-until
                                  (or bl-until (:blacklisted-until ps)))))))
