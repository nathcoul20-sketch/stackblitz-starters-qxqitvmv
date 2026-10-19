# Agrandit le logo (couleurs en aplat) sans flou : agrandissement 8x, re-quantification
# sur la palette du logo, puis réduction 2x anti-crénelée ; fond blanc extérieur -> transparent.
import sys
import numpy as np
from PIL import Image, ImageDraw
src, out = sys.argv[1], sys.argv[2]
pal = np.array([(0,0,0),(255,255,255),(255,241,1),(52,52,100)], float)
im = Image.open(src).convert("RGB")
W, H = im.size
big = im.resize((W*8, H*8), Image.BICUBIC)
a = np.asarray(big).astype(float)
d = ((a[:,:,None,:]-pal[None,None])**2).sum(-1)
idx = d.argmin(-1)
q = pal[idx].astype(np.uint8)
qi = Image.fromarray(q, "RGB")
# masque extérieur : remplissage depuis les coins sur la zone blanche
mask = Image.fromarray(((idx==1)*255).astype(np.uint8)).copy()
for c in [(0,0),(mask.width-1,0),(0,mask.height-1),(mask.width-1,mask.height-1)]:
    ImageDraw.floodfill(mask, c, 128)
from PIL import ImageFilter
shield = Image.fromarray(np.where(np.asarray(mask)==128,0,255).astype(np.uint8))
# le liseré blanc extérieur du blason a été mangé par le remplissage : on le redessine
ring = shield.filter(ImageFilter.MaxFilter(21))
rgba = Image.new("RGBA", qi.size, (255,255,255,0))
rgba.putalpha(ring)
q2 = qi.convert("RGBA"); q2.putalpha(shield)
rgba.alpha_composite(q2)
rgba = rgba.resize((W*4, H*4), Image.LANCZOS)
rgba = rgba.crop(rgba.getbbox())
rgba.save(out)
print(rgba.size)
