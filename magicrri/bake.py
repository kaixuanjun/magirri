# -*- coding: utf-8 -*-
"""把整条色彩链烘焙成**一个** 3D LUT，交给 ffmpeg 的 ``lut3d`` 逐帧套用。

这样做的理由：链上的每一步都是逐像素、与邻域无关的纯函数，
所以「还原 LUT → 解码 → 色域矩阵 → LogC3 编码 → ARRI LUT」可以预先合成，
运行时只剩一次三线性/四面体查表。4K 视频用 ffmpeg 就能实时，不必逐帧跑 Python。

链的定义（与 ``pipeline.py`` 一致，只是首尾换了编码约定）::

    Magic-Log 码值 (0..1)
      → [还原 LUT]                → Rec.709 显示码值
      → [解码 mid_transfer]       → 线性 Rec.709
      → [709→AWG3 矩阵]           → 线性 AWG3
      → [LogC3 编码]              → LogC3 码值 (0.09..0.57)
      → [ARRI LUT]                → Rec.709 显示码值 (Gamma 2.4)
      → [输出传输函数 output_transfer]

两个"编码约定"是本模块最容易出错的开关，所以都做成显式参数：

``mid_transfer``
    还原 LUT 的输出是**显示码值**，解码成线性光要用显示端的 EOTF。
    厂商 LUT 文档通常不写这一点，默认取 ``bt1886``（gamma 2.4，即监视器 EOTF）。
    若你的素材证明它其实是 BT.709 OETF，用 ``bt709``；若贴近 sRGB，用 ``srgb``。

``output_transfer``
    ARRI 的 SDR LUT 输出的是 **Gamma 2.4** 显示码值（文件名里的 ``Gamma24``）。
    现代播放器对 bt709 标记的素材套 BT.1886（≈2.4）EOTF，所以 ``keep``
    就能被正确还原；只有在需要严格 OETF 编码的交付场合才改成 ``bt709``。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Optional

import numpy as np

from .color import matrix_709_to_awg3
from .cube import LUT3D, CurveLUT, write_cube
from .transfers import logc3_encode, rec709_oetf, rec709_oetf_inverse, srgb_eotf, srgb_eotf_inverse

__all__ = ["ChainConfig", "decode_display", "encode_display", "evaluate_chain", "bake_chain"]


# --------------------------------------------------------------------------
# 显示码值 <-> 线性
# --------------------------------------------------------------------------

DECODE_MODES = ("bt1886", "bt709", "srgb")


def decode_display(code, mode: str):
    """显示码值 -> 线性光。"""
    v = np.asarray(code, dtype=np.float32)
    if mode == "bt1886":
        return np.power(np.maximum(v, 0.0), np.float32(2.4)).astype(np.float32, copy=False)
    if mode == "bt709":
        return rec709_oetf_inverse(v)
    if mode == "srgb":
        return srgb_eotf(v)
    raise ValueError(f"未知 mid_transfer: {mode}（可选 {'/'.join(DECODE_MODES)}）")


def encode_display(linear, mode: str):
    """线性光 -> 显示码值。"""
    v = np.asarray(linear, dtype=np.float32)
    if mode == "bt1886":
        return np.power(np.maximum(v, 0.0), np.float32(1.0 / 2.4)).astype(np.float32, copy=False)
    if mode == "bt709":
        return rec709_oetf(v)
    if mode == "srgb":
        return srgb_eotf_inverse(v)
    raise ValueError(f"未知 mid_transfer: {mode}")


# --------------------------------------------------------------------------
# 链
# --------------------------------------------------------------------------

@dataclass
class ChainConfig:
    restore: object                      # Magic-Log -> Rec.709 显示码值
    look: object                         # LogC3/AWG3 -> Rec.709 显示码值
    mid_transfer: str = "bt709"
    gamut_convert: bool = True
    output_transfer: str = "keep"         # keep | bt709 | bt1886 | srgb
    strength: float = 1.0
    # 是否套成品影调（仿阿莱）。关掉就退化成 ARRI 官方转换的原始响应
    finish_look: bool = True
    size: int = 65
    interp: str = "tetrahedral"

    def describe(self) -> Dict[str, object]:
        def _d(lut):
            d = lut.describe() if hasattr(lut, "describe") else {}
            return {"title": d.get("title", ""), "size": d.get("size"), "domain": [d.get("domain_min"), d.get("domain_max")]}

        return {
            "restore": _d(self.restore),
            "look": _d(self.look),
            "mid_transfer": self.mid_transfer,
            "gamut_convert": self.gamut_convert,
            "output_transfer": self.output_transfer,
            "strength": self.strength,
            "size": self.size,
            "interp": self.interp,
        }


# ---------------------------------------------------------------- 仿阿莱成品影调

# ARRI 官方的 LogC3→Rec.709 是"给后期留余量"的转换：LogC3 要拉到 1.0（线性 55）
# 才到纯白，手机 Log 的高光只到 0.55 左右，套完白点停在 0.68，整片发灰。
# 官方 Look Library 是专有资产、没有可再分发的开源替代，所以这里按 ARRI 的
# 色彩科学（AWG3 基色）+ 一条固定的电影感曲线，把"成品影调"直接烘进 LUT。
#
# 这条曲线是**固定参数**，不随素材变化，所以同一台机器拍的东西影调一致；
# 想要别的风格，改这里的常数重新烘焙即可。
LOOK_PIVOT = 0.46        # 支点：中灰落在哪
LOOK_CONTRAST = 1.38     # 绕支点的反差
LOOK_LIFT = 0.030        # 黑位抬升：0.03 ≈ 胶片的最低密度，不压死黑
LOOK_EXPOSURE = 0.35     # 整体提亮（伽马式，0→0 / 1→1，不会过曝）
LOOK_KNEE = 0.85         # 高光软肩起点


def arri_look(v, pivot: float = LOOK_PIVOT, contrast: float = LOOK_CONTRAST,
              lift: float = LOOK_LIFT, exposure: float = LOOK_EXPOSURE,
              knee: float = LOOK_KNEE):
    """成品影调：伽马提亮 → 支点反差 → 高光软肩 → 黑位抬升。

    全部单调，不会出现反转或色阶断层；1.0 仍是 1.0，结构上不会过曝。
    """
    k = float(2.0 ** (exposure / 2.4))
    v1 = np.power(np.clip(v, 0.0, 1.0), 1.0 / k)
    t = np.maximum(pivot + (v1 - pivot) * contrast, 0.0)
    out = np.where(t <= knee, t,
                   knee + (1.0 - knee) * (1.0 - np.exp(-(t - knee) / (1.0 - knee))))
    return np.clip(lift + (1.0 - lift) * out, 0.0, 1.0)

def evaluate_chain(cfg: ChainConfig, log_code) -> Dict[str, np.ndarray]:
    """对任意形状 (...,3) 的 Magic-Log 码值求值，返回各阶段中间量。"""
    x = np.asarray(log_code, dtype=np.float32)
    disp = cfg.restore.sample(x, cfg.interp)
    lin = decode_display(disp, cfg.mid_transfer)

    if cfg.gamut_convert:
        m = matrix_709_to_awg3().astype(np.float32)
        awg = lin @ m.T
    else:
        awg = lin

    logc3 = logc3_encode(awg)
    clipped = np.clip(logc3, 0.0, 1.0)
    look_disp = cfg.look.sample(clipped, cfg.interp)

    # 成品影调（仿阿莱）：把 ARRI 转换留下的余量收回来，运行时不需要再调
    look_disp = arri_look(look_disp) if cfg.finish_look else look_disp

    if cfg.output_transfer == "keep":
        out = look_disp
    else:
        out = encode_display(decode_display(look_disp, "bt1886"), cfg.output_transfer)

    if cfg.strength < 1.0:
        # 混合基准：同一张画面在"只做还原、不套 ARRI"时的样子（就是还原 LUT 的输出码值）
        base = encode_display(lin, cfg.mid_transfer)
        s = float(np.clip(cfg.strength, 0.0, 1.0))
        out = base + (out - base) * s

    return {
        "restore": disp,
        "linear": lin,
        "linear_awg3": awg,
        "logc3": logc3,
        "logc3_clipped": clipped,
        "output": np.clip(out, 0.0, 1.0).astype(np.float32, copy=False),
    }


def bake_chain(cfg: ChainConfig, title: str = "") -> LUT3D:
    """在 ``size``³ 网格上求值，产出可直接喂给 ffmpeg ``lut3d`` 的 3D LUT。"""
    n = int(cfg.size)
    g = np.linspace(0.0, 1.0, n, dtype=np.float32)
    r, gg, b = np.meshgrid(g, g, g, indexing="ij")
    pts = np.stack([r, gg, b], axis=-1).reshape(-1, 3)
    out = evaluate_chain(cfg, pts)["output"].reshape(n, n, n, 3)
    return LUT3D(
        data=out.astype(np.float32),
        title=title or f"baked chain: {cfg.mid_transfer}->{cfg.output_transfer} s={cfg.strength}",
    )


def write_baked(cfg: ChainConfig, path: str, title: str = "") -> LUT3D:
    lut = bake_chain(cfg, title=title)
    write_cube(lut, path, title=lut.title)
    return lut
