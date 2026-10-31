(ns png2gcode.preview
  "G-code text -> preview PNG. Reads the G-code itself, so the picture shows
  what the machine will actually do (works on any simple G0/G1 file)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.awt BasicStroke Color Graphics2D]
           [java.awt.geom Line2D$Double Rectangle2D$Double]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(defn parse-gcode
  "Moves from G-code text: [{:op :travel|:lead|:burn :x0 :y0 :x1 :y1}].
  Tracks G0/G1, G90/G91, M3/M4/M5 and S. G0 is always laser off (GRBL laser
  mode). G2/G3 arcs are drawn as straight lines."
  [text]
  (loop [lines (str/split-lines text)
         st {:x 0.0 :y 0.0 :g 0 :s 0.0 :laser false :abs true}
         acc (transient [])]
    (if-let [ln (first lines)]
      (let [code (-> ln (str/replace #"\(.*?\)" "") (str/replace #";.*" "") str/upper-case)
            words (for [[_ l v] (re-seq #"([A-Z])\s*([-+]?[0-9]*\.?[0-9]+)" code)]
                    [(first l) (Double/parseDouble v)])
            st2 (reduce (fn [st [l v]]
                          (case l
                            \G (case (long v)
                                 (0) (assoc st :g 0)
                                 (1 2 3) (assoc st :g 1)
                                 90 (assoc st :abs true)
                                 91 (assoc st :abs false)
                                 st)
                            \M (case (long v)
                                 (3 4) (assoc st :laser true)
                                 (5 2 30) (assoc st :laser false)
                                 st)
                            \S (assoc st :s v)
                            st))
                        st words)
            pick (fn [axis cur]
                   (if-let [[_ v] (first (filter #(= axis (first %)) words))]
                     (if (:abs st2) v (+ cur v))
                     cur))
            has-xy (some #(#{\X \Y} (first %)) words)
            nx (pick \X (:x st))
            ny (pick \Y (:y st))
            op (cond (zero? (:g st2)) :travel
                     (and (:laser st2) (pos? (:s st2))) :burn
                     :else :lead)]
        (recur (rest lines)
               (assoc st2 :x nx :y ny)
               (if has-xy
                 (conj! acc {:op op :x0 (:x st) :y0 (:y st) :x1 nx :y1 ny})
                 acc)))
      (persistent! acc))))

(def ^:private max-px 16000)

(defn render
  "Draw moves to a PNG. Black = burned, grey box = outline {:x :y :w :h}
  (optional), red cross = machine origin. With :all also light red = G0
  travel and light blue = laser-off G1. Returns the scale used (px per mm)."
  [moves out {:keys [scale line-width outline all] :or {scale 10 line-width 0.1}}]
  (let [xs (concat (mapcat (juxt :x0 :x1) moves)
                   (when outline [(:x outline) (+ (:x outline) (:w outline))])
                   [0.0])
        ys (concat (mapcat (juxt :y0 :y1) moves)
                   (when outline [(:y outline) (+ (:y outline) (:h outline))])
                   [0.0])
        margin 3.0
        minx (- (apply min xs) margin), maxx (+ (apply max xs) margin)
        miny (- (apply min ys) margin), maxy (+ (apply max ys) margin)
        scale (min (double scale) (/ max-px (- maxx minx)) (/ max-px (- maxy miny)))
        w (int (Math/ceil (* scale (- maxx minx))))
        h (int (Math/ceil (* scale (- maxy miny))))
        px (fn [x] (* scale (- x minx)))
        py (fn [y] (* scale (- maxy y)))
        img (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
        g ^Graphics2D (.createGraphics img)
        thin (BasicStroke. 1.0)
        draw (fn [ops color stroke]
               (.setColor g color)
               (.setStroke g stroke)
               (doseq [m moves :when (ops (:op m))]
                 (.draw g (Line2D$Double. (px (:x0 m)) (py (:y0 m)) (px (:x1 m)) (py (:y1 m))))))]
    (.setColor g Color/WHITE)
    (.fillRect g 0 0 w h)
    (when outline
      (.setColor g (Color. 160 160 160))
      (.setStroke g thin)
      (.draw g (Rectangle2D$Double. (px (:x outline)) (py (+ (:y outline) (:h outline)))
                                    (* scale (:w outline)) (* scale (:h outline)))))
    (when all
      (draw #{:travel} (Color. 255 170 170) thin)
      (draw #{:lead} (Color. 170 200 255) thin))
    (draw #{:burn} Color/BLACK
          (BasicStroke. (float (max 1.0 (* scale line-width)))
                        BasicStroke/CAP_BUTT BasicStroke/JOIN_MITER))
    (.setColor g Color/RED)
    (.setStroke g (BasicStroke. 2.0))
    (let [ox (int (px 0.0)), oy (int (py 0.0)), r 8]
      (.drawLine g (- ox r) oy (+ ox r) oy)
      (.drawLine g ox (- oy r) ox (+ oy r)))
    (.dispose g)
    (ImageIO/write img "png" (io/file out))
    scale))
