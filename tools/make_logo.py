"""Draws Hush's app icon and TV banner: a crescent moon over one glowing row, on night blue."""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 4  # supersample

def night(w, h):
    top, bottom = (34, 40, 78), (9, 11, 24)
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return g

def mark(size):
    """A quiet TV screen: an empty screen with just one row of apps, the selected one lit."""
    W = size * S
    m = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(m)
    # screen
    sx0, sy0, sx1, sy1 = W * .16, W * .24, W * .84, W * .68
    d.rounded_rectangle([sx0, sy0, sx1, sy1], radius=W * .06, outline=(225, 230, 250, 255), width=int(W * .035))
    # stand
    d.rounded_rectangle([W * .40, W * .74, W * .60, W * .775], radius=W * .02, fill=(225, 230, 250, 255))
    # glow under the selected tile
    glow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle([W * .25, W * .49, W * .43, W * .60], radius=W * .03, fill=(120, 160, 255, 200))
    m.alpha_composite(glow.filter(ImageFilter.GaussianBlur(W * .035)))
    d = ImageDraw.Draw(m)
    # one row: selected tile bright, the rest quiet
    y0, y1 = W * .50, W * .59
    d.rounded_rectangle([W * .26, y0, W * .42, y1], radius=W * .02, fill=(250, 251, 255, 255))
    d.rounded_rectangle([W * .45, y0, W * .58, y1], radius=W * .02, fill=(140, 155, 205, 150))
    d.rounded_rectangle([W * .61, y0, W * .74, y1], radius=W * .02, fill=(140, 155, 205, 90))
    # privacy shield on the screen's corner, with a cut-out ring so it sits cleanly on the frame
    cx, top, sw = W * .80, W * .56, W * .19
    def shield(c, t, w):
        h = w * 1.15
        return [(c - w / 2, t), (c + w / 2, t), (c + w / 2, t + h * .45),
                (c, t + h), (c - w / 2, t + h * .45)]
    cut = Image.new("L", (W, W), 0)
    ImageDraw.Draw(cut).polygon(shield(cx, top - W * .03, sw + W * .06), fill=255)
    cut = cut.filter(ImageFilter.GaussianBlur(W * .004))
    clear = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    m.paste(clear, (0, 0), cut)
    d = ImageDraw.Draw(m)
    d.polygon(shield(cx, top, sw), fill=(110, 200, 160, 255))
    # tick
    lw = int(W * .028)
    d.line([(cx - sw * .24, top + sw * .50), (cx - sw * .04, top + sw * .70), (cx + sw * .28, top + sw * .30)],
           fill=(250, 255, 252, 255), width=lw, joint="curve")
    return m

def icon(size):
    W = size * S
    bg = night(W, W).convert("RGBA")
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=W * .22, fill=255)
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    out.alpha_composite(mark(size))
    return out.resize((size, size), Image.LANCZOS)

def banner(w, h, font_path):
    W, H = w * S, h * S
    out = night(W, H).convert("RGBA")
    ms = int(H * .78)
    m = mark(h).resize((ms, ms), Image.LANCZOS)
    out.alpha_composite(m, (int(W * .10), (H - ms) // 2))
    d = ImageDraw.Draw(out)
    f = ImageFont.truetype(font_path, int(H * .26))
    text = "hush"
    tb = d.textbbox((0, 0), text, font=f)
    x = int(W * .10) + ms + int(W * .03)
    y = (H - (tb[3] - tb[1])) // 2 - tb[1]
    d.text((x, y), text, font=f, fill=(240, 243, 255, 255))
    return out.resize((w, h), Image.LANCZOS).convert("RGB")

if __name__ == "__main__":
    font = sys.argv[1]
    icon(192).save("res/drawable-xhdpi/icon.png", optimize=True)
    banner(640, 360, font).save("res/drawable-xhdpi/banner.png", optimize=True)
    icon(512).save("preview/logo.png", optimize=True)
