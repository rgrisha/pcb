(defproject png2gcode "0.1.0"
  :description "PNG -> raster laser G-code for a 3018 (GRBL 1.1 laser mode)"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/tools.cli "1.1.230"]]
  :main png2gcode.core
  :global-vars {*warn-on-reflection* true}
  :profiles {:uberjar {:aot :all
                       :uberjar-name "png2gcode.jar"}})
