"""Generates the bundled scenic wallpapers: night scenes in the same flat, layered style: ridges, dunes, firs, peaks, a sea and a skyline.

Run from the repository root:  python3 tools/make_scenes.py
Needs Pillow. Output goes to assets/wallpapers/ at 1920x1080, the size the launcher draws at.
Each scene keeps the top right (clock) and the bottom third (tiles) dark.
"""
import math
import random
from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 1920, 1080
S = 2  # ridges are drawn at twice the size and shrunk, for smooth edges


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(stops):
    col = Image.new("RGB", (1, H))
    for y in range(H):
        t = y / H
        c = stops[-1][1]
        for (a, ca), (b, cb) in zip(stops, stops[1:]):
            if a <= t <= b:
                c = lerp(ca, cb, (t - a) / (b - a))
                break
        col.putpixel((0, y), c)
    return col.resize((W, H))


def glow(box, colour, blur):
    layer = Image.new("RGB", (W, H), 0)
    ImageDraw.Draw(layer).ellipse(box, fill=colour)
    return layer.filter(ImageFilter.GaussianBlur(blur))


def stars(img, seed, count=260, reach=0.5):
    random.seed(seed)
    layer = Image.new("RGB", (W, H), 0)
    d = ImageDraw.Draw(layer)
    for _ in range(count):
        x, y = random.randint(0, W), int(random.random() ** 1.7 * H * reach)
        b = int(random.randint(60, 190) * (1 - y / (H * (reach + 0.05))))
        r = random.choice([0, 0, 0, 1])
        d.ellipse([x - r, y - r, x + r, y + r], fill=(b, b, min(255, b + 20)))
    return ImageChops.add(img, layer.filter(ImageFilter.GaussianBlur(0.6)))


def ridge_mask(base, amp, seed, rough):
    random.seed(seed)
    ph = [random.uniform(0, 6.28) for _ in range(5)]
    pts = []
    for x in range(0, W * S + 8, 8):
        u = x / (W * S)
        y = base + amp * (0.55 * math.sin(u * 5.1 + ph[0]) + 0.28 * math.sin(u * 11.3 + ph[1])
                          + 0.12 * math.sin(u * 23.7 + ph[2]) + rough * 0.06 * math.sin(u * 61 + ph[3])
                          + rough * 0.03 * math.sin(u * 140 + ph[4]))
        pts.append((x, y * S))
    m = Image.new("L", (W * S, H * S), 0)
    ImageDraw.Draw(m).polygon(pts + [(W * S, H * S), (0, H * S)], fill=255)
    return m.resize((W, H), Image.LANCZOS)


def ridges(img, colours, seed):
    """Five ridges from far to near; each is a little hazy just under its crest."""
    shape = [(0.55, 70, 1.0), (0.60, 80, 1.0), (0.66, 85, 0.8), (0.73, 70, 0.6), (0.81, 55, 0.4)]
    for i, ((base, amp, rough), col) in enumerate(zip(shape, colours)):
        m = ridge_mask(base * H, amp, seed + i * 13, rough)
        img = Image.composite(Image.new("RGB", (W, H), col), img, m)
        haze = Image.new("RGB", (W, H), 0)
        ImageDraw.Draw(haze).rectangle([0, int((base + 0.02) * H), W, int((base + 0.10) * H)],
                                       fill=tuple(c // 5 for c in col))
        img = ImageChops.add(img, ImageChops.multiply(haze.filter(ImageFilter.GaussianBlur(40)), m.convert("RGB")))
    return img


def finish(img, name, seed):
    """Darkens the bottom for the tiles, adds a soft vignette and a little grain, and saves."""
    v = Image.new("L", (1, H))
    for y in range(H):
        v.putpixel((0, y), int(255 * (1 - 0.75 * max(0, (y / H - 0.74) / 0.26) ** 1.3)))
    img = ImageChops.multiply(img, v.resize((W, H)).convert("RGB"))
    vg = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vg).ellipse([-500, -420, W + 500, H + 420], fill=255)
    vg = vg.filter(ImageFilter.GaussianBlur(260)).point(lambda p: 150 + p * 105 // 255)
    img = ImageChops.multiply(img, vg.convert("RGB"))
    random.seed(seed)
    noise = Image.effect_noise((W, H), 6).convert("RGB").point(lambda p: max(0, p - 126) // 3)
    ImageChops.add(img, noise).save(f"assets/wallpapers/{name}.jpg", quality=93)
    print("made", name)


def dusk_ridge():
    img = gradient([(0.00, (4, 6, 18)), (0.24, (12, 14, 40)), (0.42, (40, 24, 70)),
                    (0.52, (104, 44, 84)), (0.59, (190, 84, 70)), (0.65, (236, 150, 92))])
    img = ImageChops.add(img, glow([300, 440, 900, 860], (120, 60, 30), 170))
    img = stars(img, 7)
    return ridges(img, [(92, 46, 84), (58, 30, 66), (32, 20, 48), (16, 12, 30), (7, 6, 15)], 11)


def blue_hour():
    img = gradient([(0.00, (2, 5, 16)), (0.26, (6, 16, 44)), (0.44, (16, 40, 86)),
                    (0.54, (44, 84, 132)), (0.60, (110, 140, 170)), (0.66, (196, 190, 176))])
    img = ImageChops.add(img, glow([980, 430, 1560, 840], (40, 44, 40), 180))
    img = stars(img, 19)
    return ridges(img, [(52, 82, 122), (30, 54, 92), (16, 34, 64), (8, 18, 38), (4, 8, 18)], 41)


def northern_lights():
    img = gradient([(0.00, (2, 5, 12)), (0.30, (4, 12, 24)), (0.50, (6, 26, 34)),
                    (0.60, (10, 44, 46)), (0.66, (20, 66, 60))])
    img = stars(img, 29, count=320, reach=0.56)
    # Curtains of light: thin vertical rays along two wavy bands, brightest at the foot and
    # fading upwards, then softened a little.
    random.seed(31)
    aur = Image.new("RGB", (W, H), 0)
    d = ImageDraw.Draw(aur)
    for band, (col, y0, amp) in enumerate([((30, 170, 110), 380, 80), ((70, 70, 160), 270, 55)]):
        ph = random.uniform(0, 6.28)
        for x in range(-40, W - 560, 5):  # stops short of the clock's corner
            u = x / W
            y = y0 + amp * math.sin(u * 6 + ph) + 34 * math.sin(u * 17 + ph * 2)
            k = (0.30 + 0.70 * abs(math.sin(u * 8 + ph + band))) * random.uniform(0.6, 1.0)
            k *= min(1.0, (W - 560 - x) / 400)  # fades out towards the clock
            length = int(random.randint(170, 330) * (0.5 + k / 2))
            for i in range(0, length, 6):
                f = k * (1 - i / length) ** 1.6
                d.rectangle([x, y - i - 6, x + 4, y - i], fill=tuple(int(c * f) for c in col))
    aur = aur.filter(ImageFilter.GaussianBlur(9))
    img = ImageChops.add(img, aur)
    return ridges(img, [(18, 62, 62), (12, 42, 48), (8, 28, 36), (5, 16, 24), (3, 7, 12)], 53)


def moonrise():
    img = gradient([(0.00, (5, 6, 14)), (0.30, (14, 14, 30)), (0.48, (34, 28, 48)),
                    (0.58, (70, 52, 66)), (0.66, (120, 92, 88))])
    img = stars(img, 43, count=200)
    cx, cy, r = 520, 400, 74
    img = ImageChops.add(img, glow([cx - 330, cy - 330, cx + 330, cy + 330], (44, 40, 40), 120))
    img = ImageChops.add(img, glow([cx - 130, cy - 130, cx + 130, cy + 130], (70, 64, 58), 40))
    moon = Image.new("RGB", (W * S, H * S), 0)
    ImageDraw.Draw(moon).ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], fill=(236, 228, 210))
    img = ImageChops.lighter(img, moon.resize((W, H), Image.LANCZOS))
    return ridges(img, [(66, 54, 72), (44, 36, 54), (26, 22, 38), (14, 12, 24), (6, 6, 12)], 67)


# ---------------------------------------------------------------- other designs

def layer(img, pts, col, haze_from=None):
    """Fills everything under a crest line (points at twice size) with one colour, hazy under the crest."""
    m = Image.new("L", (W * S, H * S), 0)
    ImageDraw.Draw(m).polygon(pts + [(W * S, H * S), (0, H * S)], fill=255)
    m = m.resize((W, H), Image.LANCZOS)
    img = Image.composite(Image.new("RGB", (W, H), col), img, m)
    if haze_from is not None:
        haze = Image.new("RGB", (W, H), 0)
        ImageDraw.Draw(haze).rectangle([0, int(haze_from + 0.02 * H), W, int(haze_from + 0.10 * H)],
                                       fill=tuple(c // 5 for c in col))
        img = ImageChops.add(img, ImageChops.multiply(haze.filter(ImageFilter.GaussianBlur(40)), m.convert("RGB")))
    return img


def dune_line(base, amp, seed):
    """Long, smooth, slightly lopsided swells."""
    random.seed(seed)
    p = [random.uniform(0, 6.28) for _ in range(3)]
    pts = []
    for x in range(0, W * S + 8, 8):
        u = x / (W * S)
        a = math.sin(u * 3.1 + p[0])
        y = base + amp * (0.70 * (a - 0.25 * math.sin(2 * (u * 3.1 + p[0]))) + 0.30 * math.sin(u * 6.3 + p[1])
                          + 0.05 * math.sin(u * 14 + p[2]))
        pts.append((x, y * S))
    return pts


def peak_line(base, amp, seed):
    """A jagged skyline of sharp summits, made by repeatedly splitting and nudging a line."""
    random.seed(seed)
    # Summits first: a handful of pointed peaks of different heights, joined by straight slopes.
    xs, x = [], -0.05
    while x < 1.05:
        xs.append(x)
        x += random.uniform(0.07, 0.16)
    knots = []
    for i, x in enumerate(xs):
        knots.append((x, -random.uniform(0.35, 1.0)))                     # a summit
        knots.append((x + random.uniform(0.03, 0.06), random.uniform(0.0, 0.35)))  # the saddle after it
    knots.sort()
    # Then each slope is broken up a little, so the edges read as rock rather than ruled lines.
    pts = []
    for (x0, y0), (x1, y1) in zip(knots, knots[1:]):
        steps = max(2, int((x1 - x0) * 90))
        for k in range(steps):
            t = k / steps
            jitter = random.uniform(-0.035, 0.035) if 0 < k else 0
            pts.append(((x0 + (x1 - x0) * t) * W * S, (base + amp * (y0 + (y1 - y0) * t + jitter)) * S))
    return [(-10, pts[0][1])] + pts + [(W * S + 10, pts[-1][1])]


def pine_line(base, amp, seed, size):
    """A rolling crest with a row of pointed firs standing on it."""
    random.seed(seed)
    p = [random.uniform(0, 6.28) for _ in range(2)]

    def crest(x):
        u = x / W
        return base + amp * (0.7 * math.sin(u * 4.3 + p[0]) + 0.3 * math.sin(u * 9.7 + p[1]))

    pts, x = [(0, crest(0) * S)], -size
    while x < W + size:
        wd = size * random.uniform(0.34, 0.50)
        ht = size * random.uniform(0.75, 1.35)
        y = crest(x)
        # Each fir is a stack of three widening tiers.
        pts += [((x - wd) * S, y * S), ((x - wd * 0.45) * S, (y - ht * 0.42) * S),
                ((x - wd * 0.70) * S, (y - ht * 0.40) * S), ((x - wd * 0.25) * S, (y - ht * 0.74) * S),
                ((x - wd * 0.45) * S, (y - ht * 0.72) * S), (x * S, (y - ht) * S),
                ((x + wd * 0.45) * S, (y - ht * 0.72) * S), ((x + wd * 0.25) * S, (y - ht * 0.74) * S),
                ((x + wd * 0.70) * S, (y - ht * 0.40) * S), ((x + wd * 0.45) * S, (y - ht * 0.42) * S),
                ((x + wd) * S, y * S)]
        x += wd * random.uniform(1.5, 2.6)
    return pts + [(W * S, crest(W) * S)]


def ember_dunes():
    img = gradient([(0.00, (8, 4, 8)), (0.26, (26, 8, 14)), (0.44, (74, 18, 20)),
                    (0.55, (150, 46, 24)), (0.62, (224, 110, 40)), (0.67, (250, 176, 90))])
    img = ImageChops.add(img, glow([520, 470, 1100, 860], (110, 50, 16), 170))
    img = stars(img, 71, count=200, reach=0.42)
    cols = [(120, 40, 26), (84, 26, 22), (52, 16, 18), (28, 9, 12), (12, 4, 7)]
    for i, (base, amp) in enumerate([(0.60, 46), (0.65, 60), (0.71, 70), (0.78, 60), (0.86, 44)]):
        img = layer(img, dune_line(base * H, amp, 300 + i * 17), cols[i], base * H)
    return img


def emerald_pines():
    img = gradient([(0.00, (2, 8, 10)), (0.28, (4, 20, 24)), (0.46, (10, 44, 46)),
                    (0.57, (30, 84, 76)), (0.63, (92, 140, 112)), (0.68, (176, 190, 150))])
    img = ImageChops.add(img, glow([900, 460, 1500, 860], (30, 44, 30), 180))
    img = stars(img, 83, count=240)
    cols = [(34, 84, 78), (20, 58, 58), (12, 38, 42), (6, 22, 26), (3, 10, 13)]
    for i, (base, amp, size) in enumerate([(0.60, 50, 26), (0.66, 60, 40), (0.73, 60, 62), (0.81, 50, 92), (0.90, 36, 132)]):
        img = layer(img, pine_line(base * H, amp, 400 + i * 19, size), cols[i], base * H)
    return img


def alpine_peaks():
    img = gradient([(0.00, (4, 4, 16)), (0.26, (14, 10, 40)), (0.44, (44, 22, 78)),
                    (0.54, (104, 44, 110)), (0.61, (180, 90, 130)), (0.67, (240, 170, 160))])
    img = ImageChops.add(img, glow([1000, 430, 1500, 800], (70, 30, 50), 170))
    img = stars(img, 97, count=280)
    cols = [(96, 60, 122), (64, 40, 96), (38, 24, 66), (18, 12, 36), (8, 6, 16)]
    for i, (base, amp) in enumerate([(0.60, 170), (0.66, 150), (0.73, 120), (0.80, 90), (0.88, 60)]):
        img = layer(img, peak_line(base * H, amp, 500 + i * 23), cols[i], (base + 0.01) * H)
    return img


def still_water():
    """A moon over a flat sea, with its light broken into a path on the water."""
    horizon = int(H * 0.60)
    img = gradient([(0.00, (3, 6, 16)), (0.28, (8, 18, 40)), (0.46, (22, 44, 76)),
                    (0.56, (60, 86, 112)), (0.60, (120, 130, 140)), (0.601, (10, 22, 36)),
                    (0.80, (4, 10, 18)), (1.00, (2, 4, 8))])
    img = stars(img, 101, count=240, reach=0.50)
    cx, cy, r = 560, 330, 60
    img = ImageChops.add(img, glow([cx - 300, cy - 300, cx + 300, cy + 300], (36, 44, 54), 120))
    moon = Image.new("RGB", (W * S, H * S), 0)
    ImageDraw.Draw(moon).ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], fill=(232, 236, 232))
    img = ImageChops.lighter(img, moon.resize((W, H), Image.LANCZOS))
    # A far shore, low on the horizon.
    img = layer(img, [(x, (horizon - 4 - 16 * max(0.0, math.sin(x / (W * S) * 7.0 + 1.0))
                           - 8 * max(0.0, math.sin(x / (W * S) * 19.0))) * S) for x in range(0, W * S + 8, 8)]
                + [(W * S, horizon * S), (0, horizon * S)], (10, 20, 34))
    sea = Image.new("RGB", (W, H), 0)
    ImageDraw.Draw(sea).rectangle([0, horizon, W, H], fill=(8, 18, 30))
    img = ImageChops.lighter(img, sea)
    # The moon's path: short bright strokes, wider and fainter towards the viewer.
    random.seed(103)
    path = Image.new("RGB", (W, H), 0)
    d = ImageDraw.Draw(path)
    for y in range(horizon + 2, H - 60, 3):
        t = (y - horizon) / (H - horizon)
        for _ in range(5):
            half = random.uniform(3, 9) * (1 + 3.0 * t)
            x = cx + random.gauss(0, 8 + 70 * t)
            b = (1 - t) ** 2.0 * random.uniform(0.25, 1.0)
            d.ellipse([x - half, y - 0.8, x + half, y + 0.8], fill=(int(200 * b), int(210 * b), int(216 * b)))
    # A soft sheen under the strokes ties them into one band of light.
    sheen = Image.new("RGB", (W, H), 0)
    ImageDraw.Draw(sheen).polygon([(cx - 14, horizon), (cx + 14, horizon), (cx + 150, H - 120), (cx - 150, H - 120)],
                                  fill=(26, 32, 38))
    fade = Image.new("L", (1, H))
    for y in range(H):
        fade.putpixel((0, y), int(255 * max(0.0, 1 - (y - horizon) / (H - 120 - horizon)) ** 1.2) if y >= horizon else 0)
    sheen = ImageChops.multiply(sheen.filter(ImageFilter.GaussianBlur(26)), fade.resize((W, H)).convert("RGB"))
    img = ImageChops.add(ImageChops.add(img, sheen), path.filter(ImageFilter.GaussianBlur(1.1)))
    return img


def city_lights():
    """A far skyline at night: three ranks of towers with a scatter of lit windows."""
    img = gradient([(0.00, (3, 4, 14)), (0.28, (8, 10, 34)), (0.46, (24, 18, 62)),
                    (0.56, (64, 30, 86)), (0.63, (130, 60, 96)), (0.68, (206, 120, 110))])
    img = ImageChops.add(img, glow([600, 480, 1300, 860], (60, 24, 40), 180))
    img = stars(img, 107, count=200, reach=0.44)
    random.seed(109)
    lights = Image.new("RGB", (W, H), 0)
    ld = ImageDraw.Draw(lights)
    ranks = [(0.66, 60, 130, (58, 34, 82), 0), (0.72, 80, 190, (30, 20, 54), 0.045), (0.80, 100, 240, (12, 9, 26), 0.07)]
    for base, lo, hi, col, lit in ranks:
        pts, x = [], 0
        towers = []
        while x < W:
            wd = random.randint(38, 110)
            top = base * H - random.randint(lo, hi) * random.choice([0.35, 0.6, 0.6, 1.0])
            gap = random.choice([0, 0, 6, 14])
            low = base * H + 30
            pts += [(x * S, low * S), (x * S, top * S), ((x + wd) * S, top * S), ((x + wd) * S, low * S)]
            towers.append((x, top, wd))
            x += wd + gap
        img = layer(img, pts, col, base * H)
        for x, top, wd in towers:
            for wy in range(int(top) + 12, int(base * H) + 20, 15):
                for wx in range(x + 9, x + wd - 10, 12):
                    if random.random() < lit:
                        k = random.uniform(0.30, 0.75)
                        ld.rectangle([wx, wy, wx + 3, wy + 5], fill=tuple(int(c * k) for c in (250, 206, 130)))
    img = ImageChops.add(img, lights.filter(ImageFilter.GaussianBlur(5)).point(lambda p: p // 3))
    return ImageChops.add(img, lights.filter(ImageFilter.GaussianBlur(0.5)))


if __name__ == "__main__":
    for i, make in enumerate([dusk_ridge, blue_hour, northern_lights, moonrise,
                              ember_dunes, emerald_pines, alpine_peaks, still_water, city_lights]):
        finish(make(), make.__name__, 200 + i)
