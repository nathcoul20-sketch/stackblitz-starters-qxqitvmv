# Générateur de la transition ECVB

`gen.py` dessine la transition image par image (PNG 1920x1080 avec transparence).
Styles : `shutter` (utilisé dans l'appli) et `slash`.

```bash
mkdir -p out
python3 gen.py out shutter logo_ecvb.png
# Vidéo « côte à côte » attendue par l'appli : couleurs à gauche, transparence à droite
ffmpeg -framerate 30 -i out/frame_%03d.png -filter_complex \
  "[0:v]scale=960:540:flags=lanczos,format=rgba,split[c][a];[c]format=yuv420p[cc];[a]format=rgba,alphaextract,format=yuv420p[aa];[cc][aa]hstack=inputs=2,format=yuv420p" \
  -c:v libx264 -profile:v main -level 4.0 -crf 16 -r 30 -movflags +faststart ../../app/src/main/res/raw/transition.mp4
```

Si la durée change, ajuster `CUT_MS` dans `SceneController.kt` (moment où l'écran est entièrement couvert).
