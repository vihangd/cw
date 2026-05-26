(ns cw.pricing
  "Cost computation from token usage. Rates in config :pricing are USD per
   1M tokens. Returns nil (unknown) rather than 0.0 when rates or usage are
   missing — nil is honest, 0.0 is a lie.")

(defn cost-usd
  "config        — full merged config
   pricing-key   — provider's :pricing-key (e.g. \"claude\")
   model         — model string
   usage         — {:input-tokens N :output-tokens N :cached-input-tokens N}
   Returns a double, or nil if rates/usage unavailable."
  [config pricing-key model usage]
  (let [rates (get-in config [:pricing pricing-key model])]
    (when (and rates usage)
      (let [in   (or (:input-tokens usage) 0)
            out  (or (:output-tokens usage) 0)
            cin  (or (:cached-input-tokens usage) 0)
            r-in  (:input rates)
            r-out (:output rates)
            r-cin (:cached-input rates r-in)]
        (when (and r-in r-out)
          (/ (+ (* in r-in) (* out r-out) (* cin r-cin))
             1000000.0))))))
