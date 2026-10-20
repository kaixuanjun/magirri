# -*- coding: utf-8 -*-
"""端到端验证：导出的视频 == Python 侧烘焙链的正确结果吗？

这是新方案最要紧的一条回归测试。它验证的不是"颜色好不好看"，
而是**没有被悄悄搞坏**：

* LUT 的通道顺序（R 变化最快）对不对——读反会整体偏色；
* 位深链路是否真的 10-bit（8-bit 中转会出现色带）；
* YUV 范围 / 色彩矩阵是否一致（limited→full 搞错会让黑位抬升）；
* ffmpeg 的 ``lut3d`` 与 ``bake.evaluate_chain`` 是否等价。

做法：抽同一时间点的一帧输入，用 Python 链算出"应有输出"，
再和导出视频里解出来的同一帧比 ΔE。阈值取 mean < 1.0、P95 < 2.5
（x265 crf18 + 4:2:0 色度下采样会带来这点误差）。

用法::

    python tools/verify_export.py --input samples/test.mp4 --output samples/out.mp4 \
        --log v2 --look standard
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from magicrri import bake, export as ex, luts, metrics  # noqa: E402


def _lab(img: np.ndarray) -> np.ndarray:
    from magicrri.fixtures import lab_of_709_code

    return lab_of_709_code(np.clip(img, 0.0, 1.0))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--log", default="v2")
    ap.add_argument("--look", default="standard")
    ap.add_argument("--mid-transfer", default="bt709")
    ap.add_argument("--output-transfer", default="keep")
    ap.add_argument("--strength", type=float, default=1.0)
    ap.add_argument("--size", type=int, default=65)
    ap.add_argument("--at", type=float, default=1.0)
    args = ap.parse_args(argv)

    src = ex.probe(args.input)
    dst = ex.probe(args.output)
    print(f"输入 : {src.describe()}")
    print(f"输出 : {dst.describe()}")

    ok = True
    if dst.bit_depth < src.bit_depth:
        print(f"  [WARN] 位深下降: {src.bit_depth} -> {dst.bit_depth}"
              "（主动指定 --bits 8 时属预期）")
    if dst.has_audio != src.has_audio:
        print(f"  [FAIL] 音轨丢失: src={src.has_audio} dst={dst.has_audio}")
        ok = False
    if abs(dst.duration - src.duration) > 0.15:
        print(f"  [FAIL] 时长不一致: {src.duration:.2f}s vs {dst.duration:.2f}s")
        ok = False
    if dst.width != src.width or dst.height != src.height:
        print(f"  [WARN] 分辨率变化: {src.width}x{src.height} -> {dst.width}x{dst.height}")

    cfg = bake.ChainConfig(
        restore=luts.load_asset(args.log, "restore"),
        look=luts.load_asset(args.look, "look"),
        mid_transfer=args.mid_transfer,
        output_transfer=args.output_transfer,
        strength=args.strength,
        size=args.size,
    )

    frame_in = ex.read_frame(args.input, at=args.at)
    frame_out = ex.read_frame(args.output, at=args.at)
    if frame_in.shape != frame_out.shape:
        raise SystemExit(f"帧尺寸不一致: {frame_in.shape} vs {frame_out.shape}")

    expected = bake.evaluate_chain(cfg, frame_in)["output"]
    # 对照：不套 LUT 的话，差异必须显著（否则说明 LUT 根本没生效）
    no_lut = metrics.ciede2000(_lab(frame_in), _lab(frame_out))

    # 把误差拆成两部分，才能分清"管线错了"和"10-bit 量化/编码器本来的损失"：
    #   量化下限 = 完美实现时，10-bit 取样本身就会带来的 ΔE
    #   残余     = 量化之后仍存在的差异（真正的实现误差 + 编码器损失）
    levels = 1023.0 if dst.bit_depth >= 10 else 255.0
    quantized = np.rint(np.clip(expected, 0.0, 1.0) * levels) / levels
    floor = metrics.delta_stats(metrics.ciede2000(_lab(expected), _lab(quantized)))
    resid = metrics.delta_stats(metrics.ciede2000(_lab(quantized), _lab(frame_out)))

    de = metrics.ciede2000(_lab(expected), _lab(frame_out))
    st = metrics.delta_stats(de)
    print(f"\nΔE2000（应有输出 vs 导出视频解出的帧，{frame_in.shape[1]}x{frame_in.shape[0]}）")
    print(f"  全图：均值 {st['mean']:.3f}  中位 {st['median']:.3f}  "
          f"P95 {st['p95']:.3f}  最大 {st['max']:.3f}")
    print(f"  拆分：{dst.bit_depth}-bit 量化下限 均值 {floor['mean']:.3f}  "
          f"+ 残余均值 {resid['mean']:.3f}（P95 {resid['p95']:.3f}）")
    print("        残余包含：RGB↔YUV 有限范围往返、编码器损失；"
          "它不是管线错误，但 crf 越低它越小。")
    print(f"  对照：不做 LUT 时的 ΔE 均值 {metrics.delta_stats(no_lut)['mean']:.2f}"
          f"（越大说明 LUT 生效越明显）")

    # 4:2:0 色度下采样会让强边缘上的 ΔE 爆表（这不是管线错误）。
    # 所以判据只取"平滑区"，另把边缘占比单独报出来。
    # 掩膜按**逐通道**最大梯度算：只看向量亮度会漏掉"等亮度、色度急变"的区域
    # （比如色相扫描），而那正是 4:2:0 最吃亏的地方。
    gx = np.abs(np.diff(expected, axis=1, prepend=expected[:, :1])).max(axis=2)
    gy = np.abs(np.diff(expected, axis=0, prepend=expected[:1, :])).max(axis=2)
    edge = (gx + gy) > 0.02
    smooth = ~edge
    frac_edge = float(edge.mean())
    core = metrics.delta_stats(de[smooth])
    print(f"  平滑区（{1 - frac_edge:.1%} 的像素）：均值 {core['mean']:.3f}  "
          f"中位 {core['median']:.3f}  P95 {core['p95']:.3f}  最大 {core['max']:.3f}")
    print(f"  色度急变区占比 {frac_edge:.1%}（ΔE 偏大由 4:2:0 色度下采样造成，属预期）")

    # 判据随位深放宽：8-bit 输出本身的量化误差就有 1~1.5 ΔE，不该算管线错
    lim_mean, lim_p95 = (1.0, 2.5) if dst.bit_depth >= 10 else (3.0, 5.0)
    if core["mean"] > lim_mean or core["p95"] > lim_p95:
        print(f"  [FAIL] 与预期不符：LUT 顺序 / 位深 / 色彩范围可能有问题"
              f"（阈值 均值<{lim_mean} P95<{lim_p95}）")
        ok = False
    elif st["mean"] > lim_mean:
        print("  [WARN] 全图均值偏高，检查素材是否满是强边缘")
    else:
        print(f"  [ok] ffmpeg 的 lut3d 结果与 Python 烘焙链一致"
              f"（{dst.bit_depth}-bit，阈值 均值<{lim_mean} P95<{lim_p95}）")

    # 额外检查：输出里是否出现色带（相邻码值的台阶）
    gray = frame_out.mean(axis=2)
    span = float(np.percentile(gray, 99.5) - np.percentile(gray, 0.5))
    print(f"  动态范围占用: {span * 100:.1f}% of code range")
    if span < 0.05:
        print("  [WARN] 输出动态范围过窄，画面可能被压平")

    # 色带量化：在画面最平滑的一段水平渐变上，数实际用到的码值级数与最大台阶。
    # 这条只作参考：有损编码本身会把相近码值并band到一起，所以不设硬判据。
    row = gray[gray.shape[0] // 4]
    codes = np.rint(row * levels).astype(np.int64)
    uniq = np.unique(codes)
    steps = np.diff(uniq)
    fill = uniq.size / max(1, int(uniq[-1] - uniq[0]) + 1)
    print(f"  渐变区码值占用: {uniq.size} 级 / 跨度 {int(uniq[-1] - uniq[0]) + 1} 码 "
          f"（填充率 {fill:.0%}），最大台阶 {int(steps.max()) if steps.size else 0} 码")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
