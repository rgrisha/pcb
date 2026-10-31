(ns png2gcode.image
  "Load a PNG and turn it into a burn mask."
  (:require [clojure.java.io :as io])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO ImageReader]
           [javax.imageio.metadata IIOMetadata]
           [org.w3c.dom Node]))

(defn- child ^Node [^Node n ^String name]
  (when n
    (loop [c (.getFirstChild n)]
      (cond (nil? c) nil
            (= name (.getNodeName c)) c
            :else (recur (.getNextSibling c))))))

(defn read-mm-per-px
  "Pixel size in mm from the PNG's pHYs chunk, or nil if it has none."
  [^File f]
  (with-open [in (ImageIO/createImageInputStream f)]
    (let [it (ImageIO/getImageReaders in)]
      (when (.hasNext it)
        (let [^ImageReader r (.next it)]
          (try
            (.setInput r in true)
            (let [^IIOMetadata meta (.getImageMetadata r 0)
                  root ^Node (.getAsTree meta "javax_imageio_1.0")
                  node (-> root (child "Dimension") (child "HorizontalPixelSize"))
                  v (some-> node .getAttributes (.getNamedItem "value") .getNodeValue)
                  mm (some-> v Double/parseDouble)]
              (when (and mm (pos? (double mm))) mm))
            (finally (.dispose r))))))))

(defn- row-mask
  "Burn flags for image row y."
  ^booleans [^ints argb w y threshold invert]
  (let [w (long w)
        threshold (double threshold)
        row (boolean-array w)
        base (* (long y) w)
        invert (boolean invert)]
    (loop [x 0]
      (when (< x w)
        (let [p (aget argb (+ base x))
              a (bit-and (bit-shift-right p 24) 0xff)
              lum (+ (* 0.299 (bit-and (bit-shift-right p 16) 0xff))
                     (* 0.587 (bit-and (bit-shift-right p 8) 0xff))
                     (* 0.114 (bit-and p 0xff)))
              white (>= lum threshold)]
          (when (and (>= a 128) (not= white invert))
            (aset row x true))
          (recur (inc x)))))
    row))

(defn- count-true ^long [^booleans row]
  (areduce row i n 0 (if (aget row i) (inc n) n)))

(defn load-mask
  "Read a PNG into {:w :h :rows :mm-per-px :burn-count}.
  :rows is a vector of boolean arrays, row 0 = top of the image, true = burn.
  A pixel burns when its brightness >= threshold (white burns); invert flips that.
  Transparent pixels (alpha < 128) never burn."
  [path {:keys [threshold invert]}]
  (let [f (io/file path)
        ^BufferedImage img (or (ImageIO/read f)
                               (throw (ex-info (str "cannot read image: " path) {})))
        w (.getWidth img)
        h (.getHeight img)
        argb (.getRGB img 0 0 w h nil 0 w)
        rows (mapv #(row-mask argb w % (double threshold) invert) (range h))]
    {:w w :h h :rows rows
     :mm-per-px (read-mm-per-px f)
     :burn-count (reduce + (map count-true rows))}))
