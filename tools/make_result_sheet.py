import numpy as np

from magicrri import export as ex, metrics
from magicrri.fixtures import lab_of_709_code


def lab(x):
    return lab_of_709_code(np.clip(x, 0.0, 1.0))


i = ex.read_frame("samples/test_magiclog_h264.mp4", at=1.0)
surf = ex.read_frame("samples/android_out_surface.mp4", at=1.0)
buf = ex.read_frame("samples/android_out.mp4", at=1.0)
d = ex.read_frame("samples/desktop_out.mp4", at=1.0)

for name, a in (("Surface 快通路", surf), ("缓冲通路", buf)):
    st = metrics.delta_stats(metrics.ciede2000(lab(a), lab(d)))
    print("%s vs 桌面  ΔE2000: 均值 %.2f 中位 %.2f P95 %.2f 最大 %.2f"
          % (name, st["mean"], st["median"], st["p95"], st["max"]))

s2 = metrics.delta_stats(metrics.ciede2000(lab(i), lab(surf)))
print("原片 vs Surface 导出  ΔE2000: 均值 %.2f" % s2["mean"])

h = d.shape[0]
bot = slice(int(h * 0.68), h)
st_b = metrics.delta_stats(metrics.ciede2000(lab(surf[bot]), lab(d[bot])))
print("下 1/3（肤色块）  ΔE2000: 均值 %.2f P95 %.2f" % (st_b["mean"], st_b["p95"]))

ex.compare_sheet(i, [("① 输入（Magic-Log 原片）", i),
                     ("② Android 导出（本机 LUT）", surf),
                     ("③ 桌面 CLI 同参数导出", d)],
                 "android/docs/shot_result.png", tile_width=560)
print("对照图: android/docs/shot_result.png")
