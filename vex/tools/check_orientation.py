"""判定 App 导出画面相对"正确朝向"被做了什么变换。

把 App 输出的帧做 8 种二面体变换（4 旋转 × 镜像），逐一与桌面正确输出比 ΔE，
取最小者即为实际发生的变换。
"""
import sys

import numpy as np

from vex import export as ex, metrics
from vex.fixtures import lab_of_709_code


def lab(x):
    return lab_of_709_code(np.clip(x, 0.0, 1.0))


def variants(img):
    out = {}
    for k in range(4):
        r = np.rot90(img, k)
        out[f"rot{k * 90}"] = r
        out[f"rot{k * 90}+mirror"] = np.fliplr(r)
    return out


def main(app_path, ref_path, at=1.0):
    a = ex.read_frame(app_path, at=at)
    d = ex.read_frame(ref_path, at=at)
    print(f"app {a.shape}  ref {d.shape}")

    best = None
    for name, v in variants(a).items():
        if v.shape != d.shape:
            # 尺寸不同说明连宽高都没对上（例如没交换）
            continue
        st = metrics.delta_stats(metrics.ciede2000(lab(v), lab(d)))
        if best is None or st["mean"] < best[1]:
            best = (name, st["mean"])
        print(f"  {name:16s} ΔE={st['mean']:6.2f}")

    if best is None:
        print("!! 宽高与参考不一致：输出尺寸就没对")
        return
    print(f"==> 最接近的变换: {best[0]}  (ΔE {best[1]:.2f})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
