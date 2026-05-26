(ns cw.state-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cw.state :as state]))

(def ^:dynamic *cfg* nil)

(use-fixtures :each
  (fn [t]
    (let [f (java.io.File/createTempFile "cw-state" ".edn")]
      (.delete f)
      (binding [*cfg* {:state-file (.getPath f)
                       :blacklist-threshold 2
                       :providers {:p {:daily-request-limit 3
                                       :reset-hour-utc 0}}}]
        (t))
      (.delete f))))

(deftest success-increments-and-clears-errors
  (state/record-success! *cfg* :p)
  (state/record-success! *cfg* :p)
  (let [st (state/load-state *cfg*)]
    (is (= 2 (get-in st [:providers :p :requests-today])))
    (is (= 0 (get-in st [:providers :p :consecutive-errors])))))

(deftest daily-limit-blocks
  (dotimes [_ 3] (state/record-success! *cfg* :p))
  (is (false? (state/can-use? (state/load-state *cfg*) :p
                              (get-in *cfg* [:providers :p])))))

(deftest blacklist-after-threshold-failures
  (state/record-failure! *cfg* :p :exec)
  (is (true? (state/can-use? (state/load-state *cfg*) :p
                             (get-in *cfg* [:providers :p]))))
  (state/record-failure! *cfg* :p :exec) ; threshold 2 reached
  (is (false? (state/can-use? (state/load-state *cfg*) :p
                              (get-in *cfg* [:providers :p])))))

(deftest daily-reset-zeroes-counter
  (state/record-success! *cfg* :p)
  (let [stale (assoc-in (state/load-state *cfg*)
                        [:providers :p :last-reset]
                        "2000-01-01T00:00:00Z")
        reset (state/maybe-reset-daily! stale :p (get-in *cfg* [:providers :p]))]
    (is (= 0 (get-in reset [:providers :p :requests-today])))))
