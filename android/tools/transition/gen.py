"""Génère des transitions (stingers) Maybéléger FC image par image, avec transparence.

Usage : python3 gen.py <dossier_sortie> <style: slash|shutter> <logo.png>
Écrit frame_000.png ... (RGBA 1920x1080) et cut.txt (image où l'écran est entièrement couvert).
"""
import math
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 1920, 1080
FPS = 30
# Couleurs Maybéléger FC (le « rouge » des volets est devenu le jaune du club)
RED = (255, 241, 0, 255)
DARK_RED = (196, 176, 0, 255)
BLACK = (8, 8, 8, 255)
WHITE = (255, 255, 255, 255)
YELLOW = (255, 210, 0, 255)
SLANT = int(H * 0.38)  # inclinaison des bandes


def ease_in_out(t):
    t = min(max(t, 0.0), 1.0)
    return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def ease_out_back(t, s=1.9):
    t = min(max(t, 0.0), 1.0) - 1
    return t * t * ((s + 1) * t + s) + 1


def ease_out(t):
    t = min(max(t, 0.0), 1.0)
    return 1 - (1 - t) ** 3


def band(draw, left, right, color):
    """Bande inclinée entre les bords gauche/droite (mesurés en bas de l'image)."""
    if right <= left:
        return
    draw.polygon([(left + SLANT, 0), (right + SLANT, 0), (right, H), (left, H)], fill=color)


def streak_texture(seed=4, white_ratio=0.3):
    """Traits de vitesse, inclinés comme les bandes (texture pour le volet jaune)."""
    rnd = random.Random(seed)
    tex = Image.new("RGBA", (W * 2, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(tex)
    for _ in range(90):
        y = rnd.uniform(-0.1, 1.1) * H
        x = rnd.uniform(0, W * 2)
        length = rnd.uniform(200, 900)
        thick = rnd.choice([2, 3, 4, 6, 10, 18])
        a = rnd.randint(25, 90)
        col = (255, 255, 255, a + 60) if rnd.random() < white_ratio else (0, 0, 0, a)
        dx = SLANT / H * thick
        d.polygon([(x, y), (x + length, y), (x + length - dx, y + thick), (x - dx, y + thick)], fill=col)
    return tex.filter(ImageFilter.GaussianBlur(1.5))


def red_fill(frame_idx, tex, offset_speed=55):
    """Fond rouge animé : dégradé + traits de vitesse qui défilent."""
    base = Image.new("RGBA", (W, H), RED)
    grad = Image.linear_gradient("L").rotate(90, expand=True).resize((W, H))
    shade = Image.new("RGBA", (W, H), DARK_RED)
    shade.putalpha(grad.point(lambda v: int(v * 0.55)))
    base.alpha_composite(shade)
    off = (frame_idx * offset_speed) % W
    base.alpha_composite(tex.crop((off, 0, off + W, H)))
    return base


def shine(logo, progress):
    """Reflet blanc qui balaie le logo (progress 0→1)."""
    lw, lh = logo.size
    stripe = Image.new("L", (lw, lh), 0)
    d = ImageDraw.Draw(stripe)
    cx = -lw * 0.4 + progress * lw * 1.8
    w = lw * 0.12
    d.polygon([(cx, 0), (cx + w, 0), (cx + w - lh * 0.5, lh), (cx - lh * 0.5, lh)], fill=150)
    stripe = stripe.filter(ImageFilter.GaussianBlur(lw * 0.02))
    stripe = ImageChops.multiply(stripe, logo.getchannel("A"))
    white = Image.new("RGBA", (lw, lh), (255, 255, 255, 0))
    white.putalpha(stripe)
    out = logo.copy()
    out.alpha_composite(white)
    return out


def place_logo(canvas, logo, scale, alpha=1.0, dx=0, dy=0, rot=0.0):
    lw, lh = logo.size
    target_h = H * 0.74 * scale
    if target_h < 4:
        return
    s = target_h / lh
    img = logo.resize((max(1, int(lw * s)), max(1, int(lh * s))), Image.LANCZOS)
    if rot:
        img = img.rotate(rot, resample=Image.BICUBIC, expand=True)
    if alpha < 1:
        img.putalpha(img.getchannel("A").point(lambda v: int(v * alpha)))
    canvas.alpha_composite(img, (int(W / 2 - img.width / 2 + dx), int(H / 2 - img.height / 2 + dy)))


def flash_ring(canvas, progress):
    """Onde de choc blanche à l'impact du logo."""
    if not 0 < progress < 1:
        return
    ring = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ring)
    r = 200 + progress * 900
    width = int(40 * (1 - progress)) + 2
    a = int(220 * (1 - progress))
    d.ellipse((W / 2 - r, H / 2 - r, W / 2 + r, H / 2 + r), outline=(255, 255, 255, a), width=width)
    canvas.alpha_composite(ring.filter(ImageFilter.GaussianBlur(3)))


# ---------------------------------------------------------------------------
# Style 1 : « slash » — bandes inclinées noire / blanche / rouge qui balaient l'écran
# ---------------------------------------------------------------------------
def render_slash(logo, n=48):
    tex = streak_texture()
    frames = []
    full = W + SLANT
    # (couleur, début entrée, début sortie) en images ; durée de chaque balayage
    layers = [("white", 0, 34), ("black", 2, 32), ("red", 5, 29)]
    sweep = 9
    for f in range(n):
        canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        for sub in (0.0, 0.33, 0.66):  # flou de mouvement : 3 sous-images
            t = f + sub
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            d = ImageDraw.Draw(layer)
            for name, t_in, t_out in layers:
                left = full - ease_in_out((t - t_in) / sweep) * (full + SLANT + 40) - 20
                right = full + 40 - ease_in_out((t - t_out) / sweep) * (full + SLANT + 80)
                if name == "red":
                    mask = Image.new("L", (W, H), 0)
                    band(ImageDraw.Draw(mask), left, right, 255)
                    red = red_fill(f, tex)
                    red.putalpha(mask)
                    layer.alpha_composite(red)
                else:
                    band(d, left - (60 if name == "white" else 25), right + (60 if name == "white" else 25),
                         WHITE if name == "white" else BLACK)
            canvas = Image.blend(canvas, layer, 1 / (1 + (sub / 0.33))) if sub else layer

        # Logo : arrive en grossissant (effet ressort), reflet, puis part avec les bandes
        if 10 <= f < 33:
            p = (f - 10) / 7
            scale = 1.6 - 0.6 * ease_out_back(p) if p < 1 else 1.0
            shake = (6 if f in (17, 18) else 0) * (1 if f % 2 else -1)
            lg = shine(logo, (f - 19) / 9) if 19 <= f <= 28 else logo
            place_logo(canvas, lg, scale, alpha=min(1.0, p * 2.5), dx=shake, dy=-shake)
            flash_ring(canvas, (f - 16) / 9)
        elif 33 <= f < 38:
            p = (f - 33) / 5
            place_logo(canvas, logo, 1.0 + 0.25 * ease_out(p), alpha=1 - p, dx=-ease_out(p) * 500)
        frames.append(canvas)
    return frames, 21


# ---------------------------------------------------------------------------
# Style 2 : « shutter » — deux volets (noir en haut, rouge en bas) qui se ferment en diagonale
# ---------------------------------------------------------------------------
def render_shutter(logo, n=50):
    tex = streak_texture(9)
    frames = []
    for f in range(n):
        canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        close = ease_in_out(f / 9)
        open_ = ease_in_out((f - 34) / 10)
        k = close - open_  # 0 = ouvert, 1 = fermé
        # Ligne de séparation : diagonale passant par le centre de l'écran
        vx, vy = W, -H * 0.75
        norm = math.hypot(vx, vy)
        vx, vy = vx / norm, vy / norm
        nx, ny = -vy, vx  # normale (vers le bas-droite)
        if ny < 0:
            nx, ny = -nx, -ny
        travel = math.hypot(W, H) * 0.6
        far = 5000

        def half_plane(side, shift):
            cx, cy = W / 2 + nx * shift, H / 2 + ny * shift
            p1 = (cx - vx * far, cy - vy * far)
            p2 = (cx + vx * far, cy + vy * far)
            return [p1, p2, (p2[0] + side * nx * far, p2[1] + side * ny * far),
                    (p1[0] + side * nx * far, p1[1] + side * ny * far)]

        # Volet du haut (noir) recule vers le haut-gauche, celui du bas (rouge) vers le bas-droite
        top = Image.new("L", (W, H), 0)
        ImageDraw.Draw(top).polygon(half_plane(-1, -(1 - k) * travel + 4), fill=255)
        bot = Image.new("L", (W, H), 0)
        ImageDraw.Draw(bot).polygon(half_plane(1, (1 - k) * travel - 4), fill=255)
        black = Image.new("RGBA", (W, H), BLACK)
        black.putalpha(top)
        red = red_fill(f, tex, 40)
        red.putalpha(bot)
        canvas.alpha_composite(red)
        canvas.alpha_composite(black)
        # Liseré blanc + étincelle sur la diagonale au moment de la fermeture
        if 0.85 < k:
            seam = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            a = int(255 * max(0.0, 1 - abs(f - 10) / 6)) if f < 20 else 140
            ImageDraw.Draw(seam).line([(W / 2 - vx * far, H / 2 - vy * far), (W / 2 + vx * far, H / 2 + vy * far)],
                                      fill=(255, 255, 255, a), width=10)
            canvas.alpha_composite(seam.filter(ImageFilter.GaussianBlur(2)))
        if 9 <= f < 35:
            p = (f - 9) / 8
            rot = (1 - ease_out_back(p)) * -25 if p < 1 else 0
            scale = 0.4 + 0.6 * ease_out_back(p) if p < 1 else 1.0
            lg = shine(logo, (f - 19) / 10) if 19 <= f <= 29 else logo
            place_logo(canvas, lg, scale, alpha=min(1.0, p * 3), rot=rot)
            flash_ring(canvas, (f - 14) / 10)
        elif 35 <= f < 40:
            p = (f - 35) / 5
            place_logo(canvas, logo, 1.0 - 0.6 * ease_out(p), alpha=1 - p)
        frames.append(canvas)
    return frames, 22


if __name__ == "__main__":
    out_dir, style, logo_path = sys.argv[1], sys.argv[2], sys.argv[3]
    logo = Image.open(logo_path).convert("RGBA")
    frames, cut = (render_slash if style == "slash" else render_shutter)(logo)
    for i, fr in enumerate(frames):
        fr.save(f"{out_dir}/frame_{i:03d}.png")
    open(f"{out_dir}/cut.txt", "w").write(str(cut))
    print(style, len(frames), "images, coupure à", cut)
