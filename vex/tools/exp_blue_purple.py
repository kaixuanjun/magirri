"""蓝为什么变紫：验证「AWG3 里蓝色出界 → 逐通道裁到 0 → 只剩红绿 → 紫」。

ARRI 的 LogC3 编码允许负值（cut 以下是线性段），但 3D LUT 的输入域只有 [0,1]，
所以如果先把 LogC3 逐通道 clip 到 [0,1]，蓝色在绿通道上的负值会被压成 0，
红通道的正值留下 —— 蓝就变成紫红了。
"""
import numpy as np

from vex import bake, cube, luts


def lab_hue(rgb):
    from vex.fixtures import lab_of_709_code
    lab = lab_of_709_code(np.clip(rgb, 0, 1))
    h = np.degrees(np.arctan2(lab[..., 2], lab[..., 1]))
    c = np.hypot(lab[..., 1], lab[..., 2])
    return float(h), float(c), float(lab[2])


def post_restore(disp, restore_out_709, look, gamut_fix):
    lin = bake.decode_display(disp, "bt709")
    awg = lin @ bake.matrix_709_to_awg3().T.astype(np.float32)
    if gamut_fix:
        # 保持色相：整体向亮度收缩，直到最小通道回到 0
        mn = np.min(awg, axis=-1, keepdims=True)
        y = (awg * np.array([0.2126, 0.7152, 0.0722], np.float32)).sum(-1, keepdims=True)
        t = np.where(mn < 0, (y - 0) / np.maximum(y - mn, 1e-6), 1.0).astype(np.float32)
        t = np.clip(t, 0.0, 1.0)
        awg = y + (awg - y) * t
    logc3 = bake.logc3_encode(awg)
    return awg, logc3, look.sample(np.clip(logc3, 0, 1), "tetrahedral")


def main():
    restore = luts.load_asset("v2")
    look = luts.load_asset("standard")

    blues = {
        "纯蓝 709 (0,0,1)": (0.0, 0.0, 1.0),
        "椅子蓝 (0.10,0.25,0.80)": (0.10, 0.25, 0.80),
        "偏青蓝 (0.10,0.45,0.85)": (0.10, 0.45, 0.85),
        "深蓝 (0.05,0.12,0.45)": (0.05, 0.12, 0.45),
        "灰蓝 (0.35,0.45,0.60)": (0.35, 0.45, 0.60),
    }

    print(f"{'颜色':28s} {'AWG3 (G最负)':>22s}  {'现状 hue°':>9s} {'修后 hue°':>9s}   Δhue")
    for name, disp in blues.items():
        d = np.array(disp, np.float32)
        _, _, out_cur = post_restore(d, d, look, gamut_fix=False)
        awg, _, out_fix = post_restore(d, d, look, gamut_fix=True)
        h0, c0, _ = lab_hue(out_cur)
        h1, c1, _ = lab_hue(out_fix)
        print(f"{name:28s} {awg[1]:+8.3f} (min {awg.min():+.3f})  {h0:9.1f} {h1:9.1f}   {h1 - h0:+.1f}")
        print(f"{'':28s} 现状 RGB {np.round(out_cur,3)}   修后 RGB {np.round(out_fix,3)}")


if __name__ == "__main__":
    main()
