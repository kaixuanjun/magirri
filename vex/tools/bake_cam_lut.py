"""烘焙「相机直出」用的 LUT：把 Magri 链里的荣耀还原换成恒等变换。

为什么这样能对上颜色：Magri 处理的是 Magic-Log 码值，第一步用荣耀还原 LUT 把它变成
Rec.709 显示码值；而一个第三方相机拿到的**已经是** Rec.709 显示码值。所以把还原那一步
换成恒等（v → v），后面整条链（解码 → 709→AWG3 → LogC3 → ARRI 官方 LUT → 仿阿莱影调）
原样保留，得到的就是同一套色彩响应。

用法：
    python tools/bake_cam_lut.py                 # 烤到 ../lume/app/src/main/assets/luts/
    python tools/bake_cam_lut.py --look classic  # 换 ARRI 风格
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from vex import bake, cube, luts

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT.parent / "lume" / "app" / "src" / "main" / "assets" / "luts"


def identity_lut(size: int) -> cube.LUT3D:
    """恒等 3D LUT：输入多少输出多少（代替荣耀还原那一步）。"""
    g = np.linspace(0.0, 1.0, size, dtype=np.float32)
    r, gg, b = np.meshgrid(g, g, g, indexing="ij")
    return cube.LUT3D(data=np.stack([r, gg, b], axis=-1).astype(np.float32))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--look", default="standard", help="ARRI 风格：standard / classic / rec2020")
    ap.add_argument("--size", type=int, default=33, help="LUT 尺寸（33 够用，手机端更省）")
    ap.add_argument("--out", default="", help="输出文件名（默认 arri_cam_<look>.cube）")
    ap.add_argument("--toe", type=float, default=0.06, help="暗部软脚强度（0=硬裁，默认 0.06）")
    ap.add_argument("--lift", type=float, default=0.02, help="黑位抬升（默认 0.02）")
    args = ap.parse_args()

    look = luts.load_asset(args.look, "look")
    cfg = bake.ChainConfig(
        restore=identity_lut(args.size),
        look=look,
        mid_transfer="bt709",
        gamut_convert=True,
        output_transfer="keep",
        finish_look=True,
        size=args.size,
        # 相机直出专用：暗部走软脚，避免 0~0.1 被压成同一个值（死黑）
        look_toe=args.toe,
        look_lift=args.lift,
    )
    name = args.out or f"arri_cam_{args.look}.cube"
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    bake.write_baked(cfg, str(OUT_DIR / name), title=f"ArcCam ARRI look ({args.look})")

    # 自检：灰轴 + 暗部分层 + 几个饱和色
    lut = cube.read_cube(str(OUT_DIR / name))
    print(f"写出 {name}  {lut.size}³  toe={args.toe} lift={args.lift}")
    for v in (0.0, 0.05, 0.10, 0.18, 0.5, 0.75, 1.0):
        out = lut.sample(np.array([[v, v, v]], np.float32), "tetrahedral")[0]
        print("  灰 %.2f -> %.4f" % (v, out.mean()))
    for label, rgb in (("红", (1, 0, 0)), ("绿", (0, 1, 0)), ("蓝", (0, 0, 1))):
        out = lut.sample(np.array([rgb], np.float32), "tetrahedral")[0]
        print("  %s %s -> %s" % (label, rgb, np.round(out, 3)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
