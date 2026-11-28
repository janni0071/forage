"""Draws Forage's app icon: a pastel pink-to-lavender rounded square with a four-pointed sparkle."""
import sys
from PIL import Image, ImageDraw, ImageFilter

S = 1024
out = sys.argv[1]
# Diagonal gradient, pastel pink (top left) to lavender (bottom right).
pink, lav = (247, 182, 207), (205, 184, 245)
grad = Image.new("RGB", (S, S))
px = grad.load()
for y in range(S):
    for x in range(S):
        t = (x + y) / (2 * (S - 1))
        px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(pink, lav))
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle((40, 40, S - 40, S - 40), radius=230, fill=255)
icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
icon.paste(grad, (0, 0), mask)

def sparkle(cx, cy, r, waist=None):
    """A four-pointed sparkle with curved, pinched sides (a squashed astroid)."""
    import math
    pts = []
    p = 3.4  # higher is thinner
    for i in range(720):
        t = 2 * math.pi * i / 720
        c, s_ = math.cos(t), math.sin(t)
        x = math.copysign(abs(c) ** p, c) * r
        y = math.copysign(abs(s_) ** p, s_) * r
        pts.append((cx + x, cy - y))
    return pts

d = ImageDraw.Draw(icon)
ink = (62, 4, 35, 255)
# A soft shadow under the big sparkle for depth.
shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(shadow).polygon(sparkle(475, 555, 300), fill=(62, 4, 35, 70))
icon = Image.alpha_composite(icon, shadow.filter(ImageFilter.GaussianBlur(18)))
d = ImageDraw.Draw(icon)
d.polygon(sparkle(460, 540, 300), fill=ink)
d.polygon(sparkle(750, 290, 125), fill=ink)
d.polygon(sparkle(765, 775, 85), fill=ink)
icon.save(out)
