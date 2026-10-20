# -*- coding: utf-8 -*-
"""测试素材夹具：ColorChecker 24 参考色块、阶跃楔、合成 Log 画面。

ColorChecker 的 8-bit sRGB 参考值取自 BabelColor 公布的 sRGB 表
（D65，平均测量值）。本管线是 Rec.709 工作流，因此这些 8-bit 值按
**Rec.709 OETF 逆函数** 解码到线性，而不是用 sRGB 曲线——两侧使用同一条
解码曲线，ΔE 才只反映管线误差。
"""

from __future__ import annotations

from typing import List, Tuple

import numpy as np

from .transfers import rec709_oetf_inverse

__all__ = [
    "COLORCHECKER_SRGB8",
    "SKIN_PATCHES",
    "NEUTRAL_PATCHES",
    "colorchecker_reference_linear",
    "colorchecker_names",
    "step_wedge_linear",
    "ramp_codes",
    "render_chart",
    "lab_of_709_code",
    "lab_of_linear",
    "reference_lab",
]


# (名称, R, G, B) —— ColorChecker Classic 24 的 sRGB 8-bit 参考值
COLORCHECKER_SRGB8: List[Tuple[str, int, int, int]] = [
    ("dark skin 深肤色", 115, 82, 68),
    ("light skin 浅肤色", 194, 150, 130),
    ("blue sky 蓝天", 98, 122, 157),
    ("foliage 绿叶", 87, 108, 67),
    ("blue flower 蓝花", 133, 128, 177),
    ("bluish green 蓝绿", 103, 189, 170),
    ("orange 橙", 214, 126, 44),
    ("purplish blue 紫蓝", 80, 91, 166),
    ("moderate red 中等红", 193, 90, 99),
    ("purple 紫", 94, 60, 108),
    ("yellow green 黄绿", 157, 188, 64),
    ("orange yellow 橙黄", 224, 163, 46),
    ("blue 蓝", 56, 61, 150),
    ("green 绿", 70, 148, 73),
    ("red 红", 175, 54, 60),
    ("yellow 黄", 231, 199, 31),
    ("magenta 品红", 187, 86, 149),
    ("cyan 青", 8, 133, 161),
    ("white 白 9.5", 243, 243, 242),
    ("neutral 8", 200, 200, 200),
    ("neutral 6.5", 160, 160, 160),
    ("neutral 5", 122, 122, 121),
    ("neutral 3.5", 85, 85, 85),
    ("black 2", 52, 52, 52),
]

#: 指导书 §4 要求单独考核的肤色色块（索引，0-based）
SKIN_PATCHES = [0, 1]
#: 中性色块（灰阶）
NEUTRAL_PATCHES = [18, 19, 20, 21, 22, 23]


def colorchecker_names() -> List[str]:
    return [row[0] for row in COLORCHECKER_SRGB8]


def colorchecker_reference_linear() -> np.ndarray:
    """参考色块 -> 线性 Rec.709（float32, 形状 (24,3)）。"""
    codes = np.array([row[1:] for row in COLORCHECKER_SRGB8], dtype=np.float32) / np.float32(255.0)
    return rec709_oetf_inverse(codes)


def lab_of_709_code(rgb_code) -> np.ndarray:
    """Rec.709 显示码值 -> CIELAB（D65）。管线两侧共用，确保可比较。"""
    from .color import linear_rgb709_to_lab

    lin = rec709_oetf_inverse(np.asarray(rgb_code, dtype=np.float32))
    return linear_rgb709_to_lab(np.clip(lin.astype(np.float64), -1.0, 4.0))


def lab_of_linear(linear_rgb) -> np.ndarray:
    """线性 Rec.709 -> CIELAB（D65）。参考侧用它，避免对线性值重复做 OETF 逆。"""
    from .color import linear_rgb709_to_lab

    return linear_rgb709_to_lab(np.clip(np.asarray(linear_rgb, dtype=np.float64), -1.0, 4.0))


def reference_lab() -> np.ndarray:
    """ColorChecker 24 的参考 Lab（把 8-bit 参考值当作 709 显示码值解码）。"""
    return lab_of_linear(colorchecker_reference_linear())


def step_wedge_linear(n: int = 64) -> np.ndarray:
    """对数分布的场景线性阶跃楔（覆盖 ~10 档），用于色阶断裂与色调曲线分析。"""
    stops = np.linspace(-6.6, 0.0, n)
    lin = 0.18 * (2.0 ** stops)
    return np.stack([lin, lin, lin], axis=-1).astype(np.float32)


def ramp_codes(bits: int = 10) -> np.ndarray:
    """完整码值斜坡：0 .. 2^bits-1 -> 归一化 [0,1]。"""
    n = 1 << bits
    v = (np.arange(n, dtype=np.float32) + 0.0) / np.float32(n - 1)
    return np.stack([v, v, v], axis=-1).astype(np.float32)


def render_chart(patch_code01, tile: int = 72, cols: int = 6, bg: float = 0.02) -> np.ndarray:
    """把 (N,3) 的**显示码值**渲染成色块图（uint8, HxWx3）。

    不做任何传递函数处理：输入被当作 Rec.709 编码值直接写进 8-bit PNG
    （PNG 查看器按 sRGB 解释，与 709 视觉上足够接近）。这样"Magic-Log 原始
    码值"和"管线输出的 709 码值"可以用同一函数渲染，便于肉眼对照。
    """
    n = len(patch_code01)
    rows = (n + cols - 1) // cols
    h, w = rows * tile, cols * tile
    img = np.full((h, w, 3), bg, dtype=np.float32)
    for i, rgb in enumerate(np.asarray(patch_code01, dtype=np.float32)):
        r, c = divmod(i, cols)
        img[r * tile:(r + 1) * tile - 4, c * tile:(c + 1) * tile - 4] = rgb
    return (np.clip(img, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def fit_chart_to(img: np.ndarray, target_hw) -> np.ndarray:
    """把色块图最近邻缩放到指定尺寸（便于和管线输出对齐）。"""
    th, tw = target_hw
    h, w = img.shape[:2]
    yi = np.clip((np.arange(th) * h // th), 0, h - 1)
    xi = np.clip((np.arange(tw) * w // tw), 0, w - 1)
    return img[yi][:, xi]
