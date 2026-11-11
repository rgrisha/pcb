
# Utility to generate gcode for laser CNC (works with 3018)

Primary purpose is PCB production

Sample usage:
```
./png2gcode.py --width 45 --line-pitch 0.1 --m3 --travel 800 --feed 800 --overscan 0 --line-repeat 4   -o data/led-blinker-w45.gcode  data/led-blinker-w45.png
```

Still contains uncatched bug - cerntain lines corrupted when using overscan option. Overscan is not important for PCB laser engraving.

