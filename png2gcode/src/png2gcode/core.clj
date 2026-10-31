(ns png2gcode.core
  "png2gcode: PNG -> raster laser G-code for a 3018 running GRBL 1.1 (laser mode)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [png2gcode.gcode :as gcode]
            [png2gcode.image :as image]
            [png2gcode.preview :as preview]
            [png2gcode.raster :as raster])
  (:gen-class))

(def bed-x 300.0)
(def bed-y 180.0)

(defn- num-opt [short long desc & {:keys [default parse validate] :or {parse parse-double}}]
  (cond-> [short long desc
           :parse-fn #(or (parse %) (throw (ex-info "not a number" {})))]
    (some? default) (into [:default default])
    validate (into [:validate validate])))

(def positive [pos? "must be greater than 0"])
(def non-negative [#(>= % 0) "must be 0 or more"])

(def cli-options
  [["-o" "--output FILE" "Output .gcode file (default: stdout)"]
   (num-opt nil "--dpi N" "Image resolution (default: from the PNG)" :validate positive)
   (num-opt nil "--width MM" "Physical image width in mm (overrides DPI)" :validate positive)
   (num-opt nil "--line-pitch MM" "Distance between scan lines, about the laser spot size"
            :default 0.1 :validate positive)
   (num-opt nil "--power S" "Laser S value while burning (GRBL $30 is full power)"
            :default 1000 :parse parse-long :validate positive)
   (num-opt nil "--feed MM/MIN" "Burn speed" :default 1500.0 :validate positive)
   (num-opt nil "--travel MM/MIN" "G0 speed, only for the time estimate" :default 3000.0
            :validate positive)
   (num-opt nil "--threshold N" "Brightness 0-255; pixels at or above it count as white"
            :default 128 :parse parse-long :validate [#(<= 0 % 255) "must be 0-255"])
   [nil "--invert" "Burn black pixels instead of white"]
   [nil "--mirror" "Flip horizontally (bottom layer)"]
   (num-opt nil "--passes N" "Repeat the whole job N times" :default 1 :parse parse-long
            :validate positive)
   (num-opt nil "--overscan MM" "Laser-off run-up and run-out at each burn" :default 2.0
            :validate non-negative)
   (num-opt nil "--x-offset MM" "Shift the job in X" :default 0.0)
   (num-opt nil "--y-offset MM" "Shift the job in Y" :default 0.0)
   [nil "--preview FILE" "Also write a preview PNG of the G-code"]
   [nil "--preview-all" "Preview also shows travel (red) and laser-off (blue) moves"]
   (num-opt nil "--preview-scale PX/MM" "Preview resolution" :default 10.0 :validate positive)
   ["-h" "--help"]])

(defn- usage [summary]
  (str/join \newline
            ["Usage:"
             "  java -jar png2gcode.jar INPUT.png [-o OUT.gcode] [options]"
             "  java -jar png2gcode.jar preview IN.gcode OUT.png [--preview-scale PX/MM] [--line-pitch MM] [--preview-all]"
             ""
             "White pixels burn (use --invert for black). Origin X0 Y0 = bottom-left of the image."
             "Needs GRBL 1.1 in laser mode ($32=1)."
             ""
             "Options:"
             summary]))

(defn- fail [msg]
  (binding [*out* *err*] (println msg))
  (System/exit 1))

(defn- warn [& xs]
  (binding [*out* *err*] (println (apply str "warning: " xs))))

(defn- check-bounds [moves]
  (let [ms (remove #(= :comment (:op %)) moves)]
    (when (seq ms)
      (let [xs (map :x ms), ys (map :y ms)
            [x0 x1 y0 y1] [(apply min xs) (apply max xs) (apply min ys) (apply max ys)]]
        (when (or (neg? x0) (neg? y0))
          (warn "job goes to negative coordinates (min X" (gcode/fmt x0) " Y" (gcode/fmt y0)
                "); overscan runs past the image edge, so add e.g. --x-offset "
                (gcode/fmt (- (min x0 0))) " if your machine can't go below 0"))
        (when (or (> x1 bed-x) (> y1 bed-y))
          (warn "job reaches X" (gcode/fmt x1) " Y" (gcode/fmt y1)
                ", beyond the 3018 work area (" (long bed-x) "x" (long bed-y) " mm)"))))))

(defn- convert [opts input]
  (let [{:keys [width dpi line-pitch power feed travel threshold invert mirror
                passes overscan x-offset y-offset output preview preview-scale]} opts
        _ (when-not (.exists (io/file input)) (fail (str "no such file: " input)))
        mask (image/load-mask input {:threshold threshold :invert invert})
        {:keys [w h mm-per-px burn-count]} mask
        px (cond width (/ width w)
                 dpi (/ 25.4 dpi)
                 mm-per-px mm-per-px
                 :else (fail "the PNG has no DPI information; pass --dpi or --width"))
        wmm (* w px), hmm (* h px)
        lines (raster/scan-lines mask {:px px :pitch line-pitch :mirror mirror})
        moves (raster/job-moves lines {:overscan overscan :passes passes
                                       :x-offset x-offset :y-offset y-offset})
        header [(str "png2gcode: " (.getName (io/file input)))
                (format "image %dx%d px, pixel %s mm, size %s x %s mm" w h
                        (gcode/fmt px) (gcode/fmt wmm) (gcode/fmt hmm))
                (str "burn " (if invert "black" "white") " pixels, threshold " threshold
                     (when mirror ", mirrored"))
                (str "line pitch " (gcode/fmt line-pitch) " mm, " (count lines)
                     " non-empty lines, S" power ", F" (gcode/fmt feed)
                     ", overscan " (gcode/fmt overscan) " mm, " passes " pass(es)")
                (str "origin X" (gcode/fmt x-offset) " Y" (gcode/fmt y-offset)
                     " = bottom-left of the image")]
        {:keys [gcode stats]} (gcode/generate moves {:power power :feed feed :travel travel
                                                     :header header})]
    (if output
      (spit output gcode)
      (do (print gcode) (flush)))
    (when (zero? burn-count) (warn "no pixels to burn (check --invert / --threshold)"))
    (check-bounds moves)
    (when preview
      (preview/render (preview/parse-gcode gcode) preview
                      {:scale preview-scale :line-width line-pitch
                       :outline {:x x-offset :y y-offset :w wmm :h hmm}
                       :all (:preview-all opts)}))
    (binding [*out* *err*]
      (println (format "%dx%d px -> %s x %s mm, %d lines, %s%% of pixels burn, ~%s min%s"
                       w h (gcode/fmt wmm) (gcode/fmt hmm) (count lines)
                       (gcode/fmt (* 100.0 (/ burn-count (double (* w h)))))
                       (gcode/fmt (:minutes stats))
                       (if preview (str ", preview " preview) ""))))))

(defn- preview-cmd [opts [in out]]
  (when-not (and in out) (fail "usage: png2gcode preview IN.gcode OUT.png"))
  (let [moves (preview/parse-gcode (slurp in))]
    (when (empty? moves) (fail "no G0/G1 moves found"))
    (let [scale (preview/render moves out {:scale (:preview-scale opts)
                                           :line-width (:line-pitch opts)
                                           :all (:preview-all opts)})]
      (binding [*out* *err*]
        (println (format "%d moves, %d burns -> %s (%s px/mm)" (count moves)
                         (count (filter #(= :burn (:op %)) moves)) out (gcode/fmt scale)))))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) (println (usage summary))
      errors (fail (str/join \newline (concat errors ["" (usage summary)])))
      (= "preview" (first arguments)) (preview-cmd options (rest arguments))
      (= 1 (count arguments)) (convert options (first arguments))
      :else (fail (usage summary)))
    (shutdown-agents)))
