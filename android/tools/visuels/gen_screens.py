"""Écrans animés Maybéléger FC (Intro, Mi-temps, Fin), en boucle parfaite.

Usage : python3 gen_screens.py <fonts_dir> <logo_hd.png> <scene: intro|halftime|outro> <dossier_sortie> [secondes]
Écrit frame_0000.png ... (RGB 1920x1080, 30 i/s). Encodage : voir README.md.

Les zones où l'appli écrit les noms, le score et les buteurs (OverlayRenderer.renderResult)
sont fixes : PLATE_Y, PLATE_L, PLATE_R ci-dessous (mêmes valeurs dans le code Kotlin).
"""
import math
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1920, 1080, 30
YELLOW = (255, 241, 0)
NAVY = (52, 52, 100)
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)

# Cartouches des noms (Mi-temps / Fin) : x de début / fin, y haut / bas
PLATE_Y = (420, 524)
PLATE_L = (150, 730)
PLATE_R = (1190, 1770)
SLANT = 26

FONTS, LOGO_PATH, SCENE, OUT = sys.argv[1:5]
SECONDS = float(sys.argv[5]) if len(sys.argv) > 5 else 10
N = int(SECONDS * FPS)


def bebas(sz):
    return ImageFont.truetype(f"{FONTS}/BebasNeue-Regular.ttf", int(sz))


def josefin(sz, wght=600):
    f = ImageFont.truetype(f"{FONTS}/JosefinSans.ttf", int(sz))
    try:
        f.set_variation_by_axes([wght])
    except Exception:
        pass
    return f


def spaced(d, xy, s, font, fill, spacing, anchor="l"):
    """Texte avec interlettrage (le titre de club en petites capitales espacées)."""
    widths = [d.textlength(c, font=font) for c in s]
    total = sum(widths) + spacing * (len(s) - 1)
    x, y = xy
    if anchor == "m":
        x -= total / 2
    for c, w in zip(s, widths):
        d.text((x, y), c, font=font, fill=fill, anchor="ls")
        x += w + spacing


# ---------------------------------------------------------------------------
# Éléments fixes
# ---------------------------------------------------------------------------
def base_background():
    """Noir, avec une lueur bleu nuit (couleur du liseré du blason) derrière le centre d'intérêt."""
    bg = Image.new("RGB", (W, H), BLACK)
    glow = Image.new("L", (W, H), 0)
    cx, cy = (1380, 540) if SCENE == "intro" else (960, 520)
    ImageDraw.Draw(glow).ellipse((cx - 760, cy - 560, cx + 760, cy + 560), fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(220))
    bg.paste(Image.new("RGB", (W, H), NAVY), (0, 0), glow)
    return bg


def halftone_tile():
    """Trame de points jaunes, plus denses vers le bas : bande décorative qui défile."""
    tile = Image.new("RGBA", (W + 240, 300), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    step = 24
    for j in range(0, 300, step):
        r = 1.5 + 6.5 * (j / 300)
        for i in range(0, W + 240, step):
            ox = step / 2 if (j // step) % 2 else 0
            d.ellipse((i + ox - r, j - r, i + ox + r, j + r), fill=YELLOW + (70,))
    return tile


def stripes(width, n=9, color=YELLOW):
    """Motif « //// » (rappel des bandes du blason)."""
    im = Image.new("RGBA", (width, 34), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    gap = width / n
    for i in range(n):
        x = i * gap
        d.polygon([(x + 12, 0), (x + 12 + gap * 0.45, 0), (x + gap * 0.45, 34), (x, 34)], fill=color + (255,))
    return im


def plate(d, x0, x1):
    y0, y1 = PLATE_Y
    pts = [(x0 + SLANT, y0), (x1, y0), (x1 - SLANT, y1), (x0, y1)]
    d.polygon(pts, fill=BLACK)
    d.line(pts + [pts[0]], fill=YELLOW, width=6, joint="curve")


def static_layer(logo):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if SCENE == "intro":
        d.text((130, 500), "LE MATCH", font=bebas(250), fill=YELLOW, anchor="ls")
        d.text((136, 640), "VA COMMENCER", font=bebas(132), fill=WHITE, anchor="ls")
        d.rectangle((136, 676, 136 + 120, 682), fill=YELLOW)
        spaced(d, (136, 740), "MAYBÉLÉGER F.C.", josefin(34, 700), WHITE, 8)
        spaced(d, (136, 786), "LE MAY · BÉGROLLES · SAINT-LÉGER", josefin(24), (190, 190, 210), 5)
    else:
        title = "MI-TEMPS" if SCENE == "halftime" else "FIN DU MATCH"
        d.text((W / 2, 335), title, font=bebas(190), fill=YELLOW, anchor="ms")
        sub = "LA 2E MI-TEMPS VA BIENTÔT COMMENCER" if SCENE == "halftime" else "MERCI D'AVOIR SUIVI LE MATCH"
        spaced(d, (W / 2, 1010), sub, josefin(26), (200, 200, 215), 6, anchor="m")
        plate(d, *PLATE_L)
        plate(d, *PLATE_R)
        # Petit logo au-dessus du titre
        lh = 112
        lg = logo.resize((int(logo.width * lh / logo.height), lh), Image.LANCZOS)
        im.alpha_composite(lg, (int(W / 2 - lg.width / 2), 40))
    return im


# ---------------------------------------------------------------------------
# Éléments animés (tous périodiques sur la durée de la boucle)
# ---------------------------------------------------------------------------
def swirl(phase, center, scale, dim=1.0):
    """Anneaux elliptiques inclinés qui tournent, comme la spirale autour du ballon du logo."""
    big = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    cx, cy = center
    rings = [  # (demi-grand axe, demi-petit axe, épaisseur, longueur d'arc, alpha, vitesse)
        (820, 250, 26, 150, 230, 1),
        (690, 205, 12, 110, 150, -1),
        (950, 300, 6, 200, 90, 1),
    ]
    for a, b, w, length, alpha, speed in rings:
        a, b, w = a * scale, b * scale, max(2, int(w * scale))
        start = (phase * 360 * speed) % 360
        box = (cx - a, cy - b, cx + a, cy + b)
        alpha = int(alpha * dim)
        d.arc(box, start, start + length, fill=YELLOW + (alpha,), width=w)
        d.arc(box, start + 180, start + 180 + length * 0.6, fill=YELLOW + (alpha // 2,), width=max(2, w // 2))
    return big.rotate(-16, resample=Image.BICUBIC, center=center)


def shine(logo, progress):
    lw, lh = logo.size
    stripe = Image.new("L", (lw, lh), 0)
    d = ImageDraw.Draw(stripe)
    cx = -lw * 0.4 + progress * lw * 1.8
    w = lw * 0.12
    d.polygon([(cx, 0), (cx + w, 0), (cx + w - lh * 0.5, lh), (cx - lh * 0.5, lh)], fill=130)
    stripe = stripe.filter(ImageFilter.GaussianBlur(lw * 0.02))
    stripe = ImageChops.multiply(stripe, logo.getchannel("A"))
    white = Image.new("RGBA", (lw, lh), (255, 255, 255, 0))
    white.putalpha(stripe)
    out = logo.copy()
    out.alpha_composite(white)
    return out


def main():
    logo = Image.open(LOGO_PATH).convert("RGBA")
    bg = base_background()
    tile = halftone_tile()
    static = static_layer(logo)
    if SCENE == "intro":
        lh = 700
        big_logo = logo.resize((int(logo.width * lh / logo.height), lh), Image.LANCZOS)
        swirl_center, swirl_scale = (1380, 560), 0.78
    else:
        lh = 760
        wm = logo.resize((int(logo.width * lh / logo.height), lh), Image.LANCZOS)
        wm.putalpha(wm.getchannel("A").point(lambda v: int(v * 0.07)))
        swirl_center, swirl_scale = (960, 500), 1.0

    for f in range(N):
        t = f / N  # 0 → 1 sur la boucle
        frame = bg.copy().convert("RGBA")
        # Trame de points en bas, qui défile vers la gauche (période = 240 px, 2 tours par boucle)
        off = int((t * 2 * 240) % 240)
        frame.alpha_composite(tile.crop((off, 0, off + W, 300)), (0, H - 300))
        if SCENE != "intro":
            frame.alpha_composite(wm, (int(W / 2 - wm.width / 2), int(H / 2 - wm.height / 2) + 20))
        frame.alpha_composite(swirl(t, swirl_center, swirl_scale, 1.0 if SCENE == "intro" else 0.6))
        # Bandes « //// » qui glissent (période = un motif)
        gap = 330 / 9
        s_off = (t * 6 * gap) % gap
        band = Image.new("RGBA", (330 + int(gap) * 2, 34), (0, 0, 0, 0))
        band.alpha_composite(stripes(330 + int(gap) * 2, 11), (0, 0))
        frame.alpha_composite(band.crop((int(gap - s_off), 0, int(gap - s_off) + 330, 34)), (1520, 70))
        frame.alpha_composite(band.crop((int(s_off), 0, int(s_off) + 330, 34)), (70, 980))
        frame.alpha_composite(static)
        if SCENE == "intro":
            # Logo : léger flottement, reflet une fois par boucle
            bob = math.sin(t * 2 * math.pi * 2) * 10
            lg = shine(big_logo, (t - 0.55) / 0.2) if 0.55 <= t <= 0.75 else big_logo
            frame.alpha_composite(lg, (int(1380 - lg.width / 2), int(560 - lg.height / 2 + bob)))
        frame.convert("RGB").save(f"{OUT}/frame_{f:04d}.png", compress_level=1)
    print(SCENE, N, "images")


if __name__ == "__main__":
    main()
