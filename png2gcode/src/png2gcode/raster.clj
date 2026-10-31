(ns png2gcode.raster
  "Burn mask -> list of moves. Pure data, no G-code text here.

  A move is {:op :travel|:lead|:burn :x mm :y mm} (the end point), or
  {:op :comment :text s}. :travel = G0 (laser off), :lead = G1 at S0
  (run-up / run-out / short gap), :burn = G1 at full power.
  Machine Y grows upward; image row 0 is the top of the image.")

(defn runs
  "Runs of true in a boolean array, as [start end-exclusive] index pairs."
  [^booleans row]
  (let [n (alength row)]
    (loop [i 0, start nil, acc (transient [])]
      (if (= i n)
        (persistent! (if start (conj! acc [start n]) acc))
        (let [b (aget row i)]
          (cond
            (and b (nil? start)) (recur (inc i) i acc)
            (and (not b) start) (recur (inc i) nil (conj! acc [start i]))
            :else (recur (inc i) start acc)))))))

(defn row-segments
  "Burn segments of one image row in mm, ascending x."
  [^booleans row px mirror]
  (let [w (alength row)
        rs (cond->> (runs row)
             mirror (map (fn [[s e]] [(- w e) (- w s)]))
             mirror reverse)]
    (mapv (fn [[s e]] [(* s px) (* e px)]) rs)))

(defn scan-lines
  "Non-empty scan lines from bottom to top: [{:y mm :segs [[x0 x1] ...]}].
  Line i is at the centre of its band, y = (i + 0.5) * pitch, and uses the
  image row under that point."
  [{:keys [h rows]} {:keys [px pitch mirror]}]
  (let [height (* h px)
        n (max 1 (long (Math/floor (+ (/ height pitch) 1e-9))))]
    (vec (for [i (range n)
               :let [y (* (+ i 0.5) pitch)
                     r (-> (Math/floor (/ (- height y) px)) long (max 0) (min (dec h)))
                     segs (row-segments (nth rows r) px mirror)]
               :when (seq segs)]
           {:y y :segs segs}))))

(defn line-moves
  "Moves for one scan line. dir is 1 (left to right) or -1 (right to left).
  The row starts with `os` mm of laser-off run-up and ends with `os` mm of
  run-out, because the head stops at the turn between rows. Between segments
  of the same row the head keeps moving: gaps up to 2*os are crossed with G1
  S0 at burn speed, longer ones with a G0 straight to the next segment."
  [{:keys [y segs]} dir os]
  (let [segs (if (pos? dir) segs (->> segs rseq (mapv (fn [[a b]] [b a]))))
        before (fn [a] (- a (* dir os)))
        after (fn [b] (+ b (* dir os)))
        lead (fn [x] {:op :lead :x x :y y})
        travel (fn [x] {:op :travel :x x :y y})]
    (loop [[[a b] & more] segs, prev nil, acc []]
      (let [acc (cond
                  (nil? prev)
                  (cond-> (conj acc (travel (before a)))
                    (pos? os) (conj (lead a)))

                  (<= (* dir (- a prev)) (* 2 os))
                  (conj acc (lead a))

                  ;; Same direction, so GRBL's look-ahead keeps the head
                  ;; moving and arrives at burn speed: no run-out/run-up.
                  :else
                  (conj acc (travel a)))
            acc (conj acc {:op :burn :x b :y y})]
        (if (seq more)
          (recur more b acc)
          (cond-> acc (pos? os) (conj (lead (after b)))))))))

(defn job-moves
  "All moves for the job: serpentine over the lines, repeated `passes` times,
  shifted by the offsets."
  [lines {:keys [overscan passes x-offset y-offset]}]
  (let [one-pass (vec (mapcat (fn [i l] (line-moves l (if (even? i) 1 -1) overscan))
                             (range) lines))
        shift (fn [m] (if (= :comment (:op m))
                        m
                        (-> m (update :x + x-offset) (update :y + y-offset))))]
    (into []
          (comp (mapcat (fn [p] (cons {:op :comment :text (str "pass " (inc p) " of " passes)}
                                      one-pass)))
                (map shift))
          (range passes))))
