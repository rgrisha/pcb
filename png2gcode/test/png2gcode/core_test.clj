(ns png2gcode.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [png2gcode.gcode :as gcode]
            [png2gcode.image :as image]
            [png2gcode.preview :as preview]
            [png2gcode.raster :as raster])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]))

(defn mask
  "Mask from strings, '#' = burn, first string = top row."
  [& rows]
  {:w (count (first rows)) :h (count rows)
   :rows (mapv (fn [s] (boolean-array (map #(= \# %) s))) rows)})

(deftest runs-test
  (is (= [[1 3] [4 5]] (raster/runs (boolean-array [false true true false true]))))
  (is (= [] (raster/runs (boolean-array [false false]))))
  (is (= [[0 2]] (raster/runs (boolean-array [true true])))))

(deftest scan-lines-test
  (let [m (mask "#..."
                "...."
                "..##"
                ".#..")]
    (testing "bottom line uses bottom row, top line uses top row"
      (is (= [{:y 0.5 :segs [[1.0 2.0]]}
              {:y 1.5 :segs [[2.0 4.0]]}
              {:y 3.5 :segs [[0.0 1.0]]}]
             (raster/scan-lines m {:px 1.0 :pitch 1.0}))))
    (testing "mirror"
      (is (= [[2.0 3.0]] (:segs (first (raster/scan-lines m {:px 1.0 :pitch 1.0 :mirror true}))))))
    (testing "pitch finer than pixels repeats rows"
      (is (= [0.25 0.75 1.25 1.75 2.25 2.75 3.25 3.75 6.25 6.75 7.25 7.75]
             (map :y (raster/scan-lines m {:px 2.0 :pitch 0.5})))))))

(deftest line-moves-test
  (testing "left to right, small gap is crossed, run-up and run-out"
    (is (= [{:op :travel :x -1.0 :y 0} {:op :lead :x 0.0 :y 0} {:op :burn :x 1.0 :y 0}
            {:op :lead :x 2.0 :y 0} {:op :burn :x 3.0 :y 0} {:op :lead :x 4.0 :y 0}]
           (raster/line-moves {:y 0 :segs [[0.0 1.0] [2.0 3.0]]} 1 1.0))))
  (testing "right to left, big gap: G0 straight to next segment, no run-out/run-up"
    (is (= [{:op :travel :x 11.0 :y 0} {:op :lead :x 10.0 :y 0} {:op :burn :x 9.0 :y 0}
            {:op :travel :x 1.0 :y 0} {:op :burn :x 0.0 :y 0} {:op :lead :x -1.0 :y 0}]
           (raster/line-moves {:y 0 :segs [[0.0 1.0] [9.0 10.0]]} -1 1.0))))
  (testing "no overscan"
    (is (= [{:op :travel :x 0.0 :y 0} {:op :burn :x 1.0 :y 0}
            {:op :travel :x 2.0 :y 0} {:op :burn :x 3.0 :y 0}]
           (raster/line-moves {:y 0 :segs [[0.0 1.0] [2.0 3.0]]} 1 0.0)))))

(defn- burns [moves] (filter #(= :burn (:op %)) moves))

(deftest job-moves-test
  (let [lines (raster/scan-lines (mask "##" "#.") {:px 1.0 :pitch 1.0})
        job (fn [p] (raster/job-moves lines {:overscan 0.5 :passes p :x-offset 10.0 :y-offset 20.0}))]
    (is (= 6 (count (burns (job 3)))))
    (testing "serpentine: second line goes right to left"
      (is (= [11.0 10.0] (map :x (burns (job 1))))))
    (is (= [20.5 21.5] (map :y (burns (job 1)))))))

(deftest fmt-test
  (is (= "1.5" (gcode/fmt 1.5)))
  (is (= "0" (gcode/fmt -0.0001)))
  (is (= "12" (gcode/fmt 12.0)))
  (is (= "0.123" (gcode/fmt 0.1234))))

(deftest gcode-roundtrip-test
  (let [lines (raster/scan-lines (mask "#.##" ".##." "#..#") {:px 0.5 :pitch 0.5})
        moves (raster/job-moves lines {:overscan 1.0 :passes 2 :x-offset 3.0 :y-offset 4.0})
        text (:gcode (gcode/generate moves {:power 800 :feed 1000 :travel 3000 :header ["t"]}))
        parsed (preview/parse-gcode text)
        ends (fn [ms op xk yk] (for [m ms :when (= op (:op m))] [(xk m) (yk m)]))]
    (is (= (ends moves :burn :x :y) (ends parsed :burn :x1 :y1)))
    (testing "no burning on G0, laser off at S0 lead moves"
      (is (every? #(re-find #"S0$|S0 " %) (filter #(re-find #"^G1.*S0" %) (clojure.string/split-lines text))))
      (is (not-any? #(re-find #"^G0.*S" %) (clojure.string/split-lines text))))))

(deftest load-mask-test
  (let [f (File/createTempFile "p2g" ".png")
        img (BufferedImage. 3 1 BufferedImage/TYPE_INT_ARGB)]
    (.deleteOnExit f)
    (.setRGB img 0 0 (unchecked-int 0xFFFFFFFF)) ; white
    (.setRGB img 1 0 (unchecked-int 0xFF000000)) ; black
    (.setRGB img 2 0 (unchecked-int 0x00FFFFFF)) ; transparent white
    (ImageIO/write img "png" f)
    (is (= [true false false] (vec (first (:rows (image/load-mask f {:threshold 128}))))))
    (is (= [false true false] (vec (first (:rows (image/load-mask f {:threshold 128 :invert true}))))))))
