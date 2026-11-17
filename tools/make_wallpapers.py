"""Generates the bundled wallpapers: quiet, dark backgrounds that sit behind the tiles.

Run from the repository root:  python3 tools/make_wallpapers.py
Needs Pillow. Output goes to assets/wallpapers/ at 1920x1080, the size the launcher draws at.
"""
import random
from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 1920, 1080


def glow(box, colour, blur):
    layer = Image.new("RGB", (W, H), 0)
    ImageDraw.Draw(layer).ellipse(box, fill=colour)
    return layer.filter(ImageFilter.GaussianBlur(blur))


def grain(img, seed, strength=3):
    """A little noise stops smooth dark gradients from showing bands on a TV."""
    random.seed(seed)
    noise = Image.effect_noise((W, H), 6).convert("RGB").point(lambda v: max(0, v - 126) // strength)
    return ImageChops.add(img, noise)


def save(img, name, seed):
    grain(img, seed).save(f"assets/wallpapers/{name}.jpg", quality=90)


def carbon():
    img = Image.new("RGB", (W, H), (3, 3, 4))
    return ImageChops.add(img, glow([-700, -750, 1300, 550], (17, 19, 24), 260))


def graphite():
    img = Image.new("RGB", (W, H), (9, 9, 10))
    img = ImageChops.add(img, glow([360, -500, 1560, 620], (30, 31, 34), 300))
    return ImageChops.subtract(img, glow([-400, 700, 2320, 1700], (9, 9, 10), 200))


def midnight():
    img = Image.new("RGB", (W, H), (2, 4, 9))
    img = ImageChops.add(img, glow([900, -600, 2500, 700], (8, 18, 44), 320))
    return ImageChops.add(img, glow([-600, 300, 500, 1300], (4, 8, 22), 300))


def ember():
    img = Image.new("RGB", (W, H), (5, 3, 3))
    img = ImageChops.add(img, glow([1100, 500, 2500, 1500], (46, 16, 6), 330))
    return ImageChops.add(img, glow([-500, -500, 700, 400], (14, 8, 8), 300))


def aurora():
    img = Image.new("RGB", (W, H), (2, 4, 5))
    img = ImageChops.add(img, glow([-300, -500, 1300, 420], (4, 34, 30), 300))
    return ImageChops.add(img, glow([1000, -300, 2400, 600], (22, 10, 40), 320))


def mist():
    """Soft horizontal bands of grey fog, lightest across the upper middle."""
    random.seed(21)
    img = Image.new("RGB", (W, H), (6, 7, 8))
    fog = Image.new("RGB", (W, H), 0)
    d = ImageDraw.Draw(fog)
    for _ in range(46):
        x, y = random.randint(-400, W), random.randint(120, 620)
        w, h = random.randint(500, 1500), random.randint(40, 150)
        g = random.randint(8, 24)
        d.ellipse([x, y, x + w, y + h], fill=(g, g, g + 1))
    return ImageChops.add(img, fog.filter(ImageFilter.GaussianBlur(70)))


if __name__ == "__main__":
    for i, make in enumerate([carbon, graphite, midnight, ember, aurora, mist]):
        save(make(), make.__name__, 100 + i)
        print("made", make.__name__)
