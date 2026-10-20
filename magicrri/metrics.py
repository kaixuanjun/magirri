# -*- coding: utf-8 -*-
"""量化指标：CIEDE2000、码值占用（色阶断裂）、通道裁剪、色调曲线统计。

CIEDE2000 实现遵循 Sharma, Wu & Dalal (2005) 的参考公式，并随附
:data:`SHARMA_TEST_PAIRS` 自检数据（见 ``tests/test_metrics.py``）。
"""

from __future__ import annotations

from typing import Iterable, Sequence

import numpy as np

__all__ = [
    "ciede2000",
    "delta_stats",
    "SHARMA_TEST_PAIRS",
    "code_occupancy",
    "clipped_fraction",
    "luminance709",
]


def _deg(x):
    return x * (180.0 / np.pi)


def _rad(x):
    return x * (np.pi / 180.0)


def ciede2000(lab1, lab2, kL: float = 1.0, kC: float = 1.0, kH: float = 1.0) -> np.ndarray:
    """两批 CIELAB 之间的 ΔE2000（逐元素，支持广播）。"""
    p1 = np.asarray(lab1, dtype=np.float64)
    p2 = np.asarray(lab2, dtype=np.float64)
    L1, a1, b1 = p1[..., 0], p1[..., 1], p1[..., 2]
    L2, a2, b2 = p2[..., 0], p2[..., 1], p2[..., 2]

    C1 = np.hypot(a1, b1)
    C2 = np.hypot(a2, b2)
    Cbar = 0.5 * (C1 + C2)
    Cbar7 = Cbar ** 7
    G = 0.5 * (1.0 - np.sqrt(Cbar7 / (Cbar7 + 25.0 ** 7)))

    a1p = (1.0 + G) * a1
    a2p = (1.0 + G) * a2
    C1p = np.hypot(a1p, b1)
    C2p = np.hypot(a2p, b2)

    h1p = np.where((b1 == 0) & (a1p == 0), 0.0, np.mod(_deg(np.arctan2(b1, a1p)), 360.0))
    h2p = np.where((b2 == 0) & (a2p == 0), 0.0, np.mod(_deg(np.arctan2(b2, a2p)), 360.0))

    dLp = L2 - L1
    dCp = C2p - C1p

    zero_chroma = (C1p * C2p) == 0.0
    dhp = h2p - h1p
    dhp = np.where(dhp > 180.0, dhp - 360.0, dhp)
    dhp = np.where(dhp < -180.0, dhp + 360.0, dhp)
    dhp = np.where(zero_chroma, 0.0, dhp)
    dHp = 2.0 * np.sqrt(C1p * C2p) * np.sin(_rad(dhp) / 2.0)

    Lbarp = 0.5 * (L1 + L2)
    Cbarp = 0.5 * (C1p + C2p)
    hsum = h1p + h2p
    hdiff = np.abs(h1p - h2p)
    hbarp = np.where(
        zero_chroma,
        hsum,
        np.where(
            hdiff <= 180.0,
            0.5 * hsum,
            np.where(hsum < 360.0, 0.5 * (hsum + 360.0), 0.5 * (hsum - 360.0)),
        ),
    )

    T = (
        1.0
        - 0.17 * np.cos(_rad(hbarp - 30.0))
        + 0.24 * np.cos(_rad(2.0 * hbarp))
        + 0.32 * np.cos(_rad(3.0 * hbarp + 6.0))
        - 0.20 * np.cos(_rad(4.0 * hbarp - 63.0))
    )
    dtheta = 30.0 * np.exp(-(((hbarp - 275.0) / 25.0) ** 2))
    RC = 2.0 * np.sqrt(Cbarp ** 7 / (Cbarp ** 7 + 25.0 ** 7))
    SL = 1.0 + (0.015 * (Lbarp - 50.0) ** 2) / np.sqrt(20.0 + (Lbarp - 50.0) ** 2)
    SC = 1.0 + 0.045 * Cbarp
    SH = 1.0 + 0.015 * Cbarp * T
    RT = -np.sin(_rad(2.0 * dtheta)) * RC

    termL = dLp / (kL * SL)
    termC = dCp / (kC * SC)
    termH = dHp / (kH * SH)
    return np.sqrt(termL ** 2 + termC ** 2 + termH ** 2 + RT * termC * termH)


# Sharma et al. (2005) 的 CIEDE2000 验证数据（序号 1-8，够做回归用）。
SHARMA_TEST_PAIRS: Sequence[tuple] = (
    # (L1, a1, b1, L2, a2, b2, expected dE00)
    (50.0000, 2.6772, -79.7751, 50.0000, 0.0000, -82.7485, 2.0425),
    (50.0000, 3.1571, -77.2803, 50.0000, 0.0000, -82.7485, 2.8615),
    (50.0000, 2.8361, -74.0200, 50.0000, 0.0000, -82.7485, 3.4412),
    (50.0000, -1.3802, -84.2814, 50.0000, 0.0000, -82.7485, 1.0000),
    (50.0000, -1.1848, -84.8006, 50.0000, 0.0000, -82.7485, 1.0000),
    (50.0000, -0.9009, -85.5211, 50.0000, 0.0000, -82.7485, 1.0000),
    (50.0000, 0.0000, 0.0000, 50.0000, -1.0000, 2.0000, 2.3669),
    (50.0000, -1.0000, 2.0000, 50.0000, 0.0000, 0.0000, 2.3669),
    (50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0009, 7.1792),
    (50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0010, 7.1792),
    (50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0011, 7.2195),
    (50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0012, 7.2195),
)


def delta_stats(values: Iterable[float]) -> dict:
    v = np.asarray(list(values), dtype=np.float64).ravel()
    if v.size == 0:
        return {"n": 0}
    return {
        "n": int(v.size),
        "mean": float(v.mean()),
        "median": float(np.median(v)),
        "p95": float(np.percentile(v, 95)),
        "max": float(v.max()),
        "min": float(v.min()),
        "stdev": float(v.std(ddof=0)),
    }


def luminance709(linear_rgb) -> np.ndarray:
    """Rec.709 亮度 Y（线性）。"""
    v = np.asarray(linear_rgb, dtype=np.float64)
    return 0.2126 * v[..., 0] + 0.7152 * v[..., 1] + 0.0722 * v[..., 2]


def code_occupancy(input_codes: np.ndarray, output_codes: np.ndarray, bits: int = 10) -> dict:
    """统计"输入码值 -> 输出码值"映射的色阶断裂情况。

    * ``unique_outputs``：输出实际用掉的码值个数。理想 ≈ 输入个数。
    * ``max_empty_run``：输出码值空间中最长的连续空档（= 最宽的色带）。
    * ``collapse_ratio``：输入码值坍缩到同一输出码值的平均比例。
    """
    inp = np.asarray(input_codes).ravel()
    out = np.asarray(output_codes).ravel().astype(np.int64)
    top = (1 << bits) - 1
    out = np.clip(out, 0, top)
    used = np.unique(out)
    if out.size <= 1:
        return {"unique_outputs": int(used.size), "max_empty_run": 0, "collapse_ratio": 1.0}
    # 相邻输出码值之间的空档
    gaps = np.diff(used) - 1
    return {
        "unique_outputs": int(used.size),
        "unique_inputs": int(np.unique(inp).size),
        "max_empty_run": int(max(0, gaps.max() if gaps.size else 0)),
        "mean_step": float(np.diff(used).mean() if gaps.size else 0.0),
        "collapse_ratio": float(np.unique(inp).size / max(1, np.unique(out).size)),
        "output_range": [int(used.min()), int(used.max())],
    }


def clipped_fraction(linear_rgb, lo: float = 0.0, hi: float = 1.0) -> dict:
    """统计落在 [lo, hi] 之外（即被硬件/中间步骤裁剪）的像素比例。"""
    v = np.asarray(linear_rgb, dtype=np.float64).reshape(-1, 3)
    over = (v > hi + 1e-9)
    under = (v < lo - 1e-9)
    return {
        "any_channel_over": float(over.any(axis=1).mean()),
        "any_channel_under": float(under.any(axis=1).mean()),
        "per_channel_over": over.mean(axis=0).tolist(),
        "per_channel_under": under.mean(axis=0).tolist(),
    }
