# 从根目录的 MAGGIRI_LOGO.png 生成 Android 各密度图标
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(".")
SRC = ROOT / "MAGGIRI_LOGO.png"
RES = ROOT / "android/app/src/main/res"

im = Image.open(SRC).convert("RGB")
arr = np.asarray(im).astype(np.float32)

# 1) 裁到字标包围盒
mask = arr.min(axis=2) < 235
ys, xs = np.where(mask)
pad = 12
box = (max(0, xs.min() - pad), max(0, ys.min() - pad),
       min(im.width, xs.max() + 1 + pad), min(im.height, ys.max() + 1 + pad))
logo = im.crop(box)

# 2) 白底转透明：按亮度反推 alpha，保留蓝色本体
l = np.asarray(logo).astype(np.float32).mean(axis=2)
alpha = np.clip((250.0 - l) / (250.0 - 80.0), 0.0, 1.0)
rgba = np.dstack([np.asarray(logo), (alpha * 255).astype(np.uint8)])
logo_a = Image.fromarray(rgba, "RGBA")

BG = (250, 252, 254)          # 与图里背景一致的极浅冷白
TEXT_W = 0.74                  # legacy 图标里字标占宽比例
FG_W = 0.66                    # adaptive 前景里字标占宽比例（安全区 66/108）

def scaled(w):
    return logo_a.resize((max(1, w), max(1, round(w * logo_a.height / logo_a.width))), Image.LANCZOS)

def composite(size, width_ratio, circle=False):
    canvas = Image.new("RGBA", (size, size), BG + (255,))
    mask_img = Image.new("L", (size, size), 0)
    if circle:
        from PIL import ImageDraw
        ImageDraw.Draw(mask_img).ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        from PIL import ImageDraw
        ImageDraw.Draw(mask_img).rounded_rectangle(
            (0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=255)
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    base.paste(canvas, (0, 0), mask_img)
    w = round(size * width_ratio)
    lg = scaled(w)
    base.alpha_composite(lg, ((size - lg.width) // 2, (size - lg.height) // 2))
    return base

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dpi, size in LEGACY.items():
    d = RES / f"mipmap-{dpi}"
    d.mkdir(parents=True, exist_ok=True)
    composite(size, TEXT_W).save(d / "ic_launcher.png")
    composite(size, TEXT_W - 0.04, circle=True).save(d / "ic_launcher_round.png")

# adaptive 前景：108dp 画布，字标放在中间
for dpi, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
    size = round(108 * scale)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    w = round(size * FG_W)
    lg = scaled(w)
    canvas.alpha_composite(lg, ((size - lg.width) // 2, (size - lg.height) // 2))
    (RES / f"mipmap-{dpi}").mkdir(parents=True, exist_ok=True)
    canvas.save(RES / f"mipmap-{dpi}" / "ic_launcher_foreground.png")

print("生成完成：legacy + round + adaptive foreground")
for p in sorted(RES.glob("mipmap-*/*.png")):
    print(" ", p.relative_to(RES), Image.open(p).size)
