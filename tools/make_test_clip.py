# -*- coding: utf-8 -*-
"""生成一段"类 Magic-Log"测试素材，用于端到端验证导出链路。

**注意**：这不是真实的荣耀 Magic-Log 录像（那种素材需要真机拍摄）。
这里把一张场景线性测试图用 LogC3 编码压成"灰平"的 Log 画面，
目的只有三个：
  1. 有一段带 10-bit / 带音轨的视频可以跑通导出流程；
  2. 画面里有平滑渐变（查色带）、饱和色（查色域裁切）、灰阶（查黑电平）；
  3. 能被同一套 LUT 还原成可读的画面，从而量出端到端误差。

用法::

    python tools/make_test_clip.py out.mp4 [--width 1280] [--height 720] [--seconds 2]
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from magicrri.bake import decode_display, encode_display  # noqa: E402
from magicrri.luts import RESTORE_LUTS, load_asset  # noqa: E402


def honor_gray_curve(key: str = "v2", n: int = 2048):
    """把荣耀还原 LUT 的灰轴取出来，并构造它的**反函数**。

    为什么要这么做：荣耀的 Magic-Log 曲线没有公开参数，中灰落点也和 LogC3
    不同（实测它的 LUT 在输入 0.5 附近才把中灰推到 0.45 左右，而 LogC3 的
    中灰在 0.391）。用 logc3_encode 硬造"假 Magic-Log"会让画面整体偏暗，
    只能测管道、不能看颜色。

    正确做法是**反解官方 LUT 的灰轴**：这样造出来的 Log 帧，被官方 LUT
    还原后恰好等于我们想要的那张画面，前后自洽。
    """
    lut = load_asset(key, "restore")
    x = np.linspace(0.0, 1.0, n, dtype=np.float32)
    y = lut.sample(np.stack([x] * 3, axis=-1))[:, 0]
    # 单调化（LUT 底部有小段平台），再做插值反解
    y = np.maximum.accumulate(y)
    keep = np.concatenate([[True], np.diff(y) > 1e-7])
    return y[keep], x[keep]


def build_scene(w: int, h: int) -> np.ndarray:
    """场景线性 (H,W,3)，值域 0..1，含渐变/饱和色/色卡块/肤色。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u = (xx / max(1, w - 1))
    v = (yy / max(1, h - 1))

    img = np.zeros((h, w, 3), dtype=np.float32)
    # 上半：中性渐变（0.0005 → 1.0，对数分布，覆盖 ~11 档）
    top = v < 0.5
    ramp = 0.0005 * (2000.0 ** u)
    for c in range(3):
        img[..., c] = np.where(top, ramp, img[..., c])

    # 下半左：色相扫描（等亮度），查色域裁切
    bottom = ~top
    hue = u * 2.0 * np.pi
    lum = 0.25
    r = lum * (1.0 + 0.8 * np.cos(hue))
    g = lum * (1.0 + 0.8 * np.cos(hue - 2.0944))
    b = lum * (1.0 + 0.8 * np.cos(hue - 4.1888))
    hsv = np.stack([np.clip(r, 0, 1), np.clip(g, 0, 1), np.clip(b, 0, 1)], axis=-1)
    img = np.where(bottom[..., None], hsv, img)

    # 右下：肤色 + 天空 + 灰卡（真实题材的代表性区域）
    bh, bw = h // 6, w // 6
    patches = [
        (0.42, 0.26, 0.20),   # 肤色（深）
        (0.62, 0.44, 0.36),   # 肤色（浅）
        (0.12, 0.22, 0.45),   # 天空蓝
        (0.18, 0.18, 0.18),   # 18% 灰
        (0.04, 0.04, 0.04),   # 暗部
        (0.90, 0.90, 0.90),   # 高光
    ]
    for i, rgb in enumerate(patches):
        y0 = h - bh - 4
        x0 = w - (i + 1) * bw - 4
        img[y0:y0 + bh, max(0, x0):max(0, x0) + bw - 4] = np.array(rgb, dtype=np.float32)

    return np.clip(img, 0.0, 1.0)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--height", type=int, default=540)
    ap.add_argument("--seconds", type=float, default=2.0)
    ap.add_argument("--fps", type=int, default=25)
    ap.add_argument("--log", default="v2", help="用哪一版荣耀还原 LUT 反解出 Log 曲线")
    ap.add_argument("--mid-transfer", default="bt709", choices=["bt1886", "bt709", "srgb"])
    args = ap.parse_args(argv)

    scene = build_scene(args.width, args.height)

    # 目标画面：把场景线性值用显示 EOTF 编码成"应该看到的码值"
    target = encode_display(scene, args.mid_transfer)
    # 再用官方 LUT 灰轴的反函数把目标码值压回 Magic-Log 码值
    lut_gray, log_code = honor_gray_curve(args.log)
    log_frame = np.interp(target, lut_gray, log_code).astype(np.float32)
    print(f"  灰轴反解：LUT 输入中灰 ≈ "
          f"{float(np.interp(0.5, lut_gray, log_code)):.3f}（输出 0.5 对应的输入码值）")
    raw = (np.clip(log_frame, 0, 1) * 65535.0 + 0.5).astype("<u2").tobytes()

    # rawvideo 输入不会自动重复，必须自己写够帧数，否则时长只有 1/fps 秒
    frames = max(1, int(round(args.seconds * args.fps)))
    tmp = os.path.join(os.environ.get("TEMP", "."), "magicrri_test_frames.raw")
    with open(tmp, "wb") as fh:
        for _ in range(frames):
            fh.write(raw)
    print(f"  生成 {frames} 帧 {args.width}x{args.height} raw ({os.path.getsize(tmp) / 1e6:.1f} MB)")

    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb48le",
        "-s", f"{args.width}x{args.height}", "-r", str(args.fps), "-i", tmp,
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={args.seconds}",
        "-vf", "format=yuv420p10le",
        "-c:v", "libx265", "-x265-params", "log-level=error", "-crf", "10",
        "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
        "-c:a", "aac", "-b:a", "128k",
        "-shortest",
        args.out,
    ]
    rc = subprocess.run(cmd).returncode
    if rc == 0:
        print(f"[ok] {args.out}  {os.path.getsize(args.out) / 1e6:.2f} MB  {frames} 帧")
    os.remove(tmp)
    return rc


if __name__ == "__main__":
    sys.exit(main())
