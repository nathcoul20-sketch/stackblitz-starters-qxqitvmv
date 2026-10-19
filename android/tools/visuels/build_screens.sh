#!/bin/bash
# Génère et encode les 3 écrans animés dans res/raw (à lancer depuis tools/visuels).
set -e
FONTS=../../app/src/main/assets/fonts
RAW=../../app/src/main/res/raw
for spec in intro:12 halftime:10 outro:10; do
  s=${spec%%:*}; sec=${spec##*:}
  tmp=$(mktemp -d)
  python3 gen_screens.py $FONTS logo_mbl_hd.png $s $tmp $sec
  ffmpeg -v error -y -framerate 30 -i $tmp/frame_%04d.png -c:v libx264 -profile:v high -level 4.1 \
    -pix_fmt yuv420p -crf 21 -preset slow -g 60 -movflags +faststart $RAW/$s.mp4
  rm -rf $tmp
done
