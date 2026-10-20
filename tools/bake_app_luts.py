# -*- coding: utf-8 -*-
"""为 Android App 烘焙内置 LUT 资产。

App 里不做任何色彩数学——桌面端把整条链烘焙好，手机端只做一次 3D 查表。
这样两侧的成片口径完全一致，也把手机端的风险降到最低。

用法::

    python tools/bake_app_luts.py [--size 33]
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from magicrri import bake, cube, luts  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "android", "app", "src", "main", "assets", "luts")

COMBOS = [
    ("honor_v2_standard.cube", "v2", "standard"),
    ("honor_v2_classic.cube", "v2", "classic"),
    ("honor_v1_standard.cube", "v1", "standard"),
    ("honor_v1_classic.cube", "v1", "classic"),
]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=33,
                    help="烘焙网格（33³≈1.2MB，65³≈9MB；手机上 33³ 够用）")
    args = ap.parse_args(argv)

    os.makedirs(OUT_DIR, exist_ok=True)
    for name, log_key, look_key in COMBOS:
        cfg = bake.ChainConfig(
            restore=luts.load_asset(log_key, "restore"),
            look=luts.load_asset(look_key, "look"),
            mid_transfer="bt709",
            output_transfer="keep",
            size=args.size,
        )
        path = os.path.join(OUT_DIR, name)
        lut = bake.bake_chain(cfg, title=f"{log_key} -> LogC3 -> ARRI {look_key}")
        cube.write_cube(lut, path, title=lut.title)
        mb = os.path.getsize(path) / 1e6
        print(f"  {name:<28} {args.size}³  {mb:.2f} MB")

    total = sum(os.path.getsize(os.path.join(OUT_DIR, n)) for n, _, _ in COMBOS)
    print(f"合计 {total / 1e6:.1f} MB -> {OUT_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
