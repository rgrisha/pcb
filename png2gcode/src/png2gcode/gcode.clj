(ns png2gcode.gcode
  "Moves -> G-code text for GRBL 1.1 in laser mode ($32=1)."
  (:require [clojure.string :as str])
  (:import [java.util Locale]))

(defn fmt
  "Number with up to 3 decimals, trailing zeros removed. Always uses '.'."
  [v]
  (let [s (String/format Locale/ROOT "%.3f" (object-array [(double v)]))
        s (-> s (str/replace #"0+$" "") (str/replace #"\.$" ""))]
    (if (= s "-0") "0" s)))

(defn generate
  "Returns {:gcode text :stats {:burn :lead :travel mm, :minutes est}}."
  [moves {:keys [power feed travel header]}]
  (let [sb (StringBuilder.)
        line! (fn [& xs] (.append sb ^String (apply str xs)) (.append sb "\n"))]
    (doseq [h header] (line! "; " h))
    (line! "G21 ; mm")
    (line! "G90 ; absolute coordinates")
    (line! "M5")
    (line! "M4 S0 ; dynamic laser power, needs GRBL laser mode $32=1")
    (loop [ms (seq moves), x 0.0, y 0.0, f nil, stats {:burn 0.0 :lead 0.0 :travel 0.0}]
      (if-let [{:keys [op] :as m} (first ms)]
        (if (= op :comment)
          (do (line! "; " (:text m))
              (recur (next ms) x y f stats))
          (let [nx (double (:x m))
                ny (double (:y m))
                xw (when (not= (fmt nx) (fmt x)) (str " X" (fmt nx)))
                yw (when (not= (fmt ny) (fmt y)) (str " Y" (fmt ny)))
                moved (or xw yw)
                g1 (not= op :travel)]
            (when moved
              (if g1
                (line! "G1" xw yw " S" (if (= op :burn) power 0)
                       (when (not= f feed) (str " F" (fmt feed))))
                (line! "G0" xw yw)))
            (recur (next ms) nx ny (if (and moved g1) feed f)
                   (update stats op + (Math/hypot (- nx x) (- ny y))))))
        (let [minutes (+ (/ (+ (:burn stats) (:lead stats)) feed)
                         (/ (:travel stats) travel))
              stats (assoc stats :minutes minutes)]
          (line! "M5")
          (line! "G0 X0 Y0")
          (line! "M2")
          (line! (format "; burn %s mm, laser-off G1 %s mm, travel %s mm, ~%s min (ignores acceleration)"
                         (fmt (:burn stats)) (fmt (:lead stats)) (fmt (:travel stats))
                         (fmt minutes)))
          {:gcode (str sb) :stats stats})))))
