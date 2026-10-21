# -*- coding: utf-8 -*-
"""合成夹具：一条"类 Magic-Log"的对数曲线、以及由它派生的还原 LUT。

**这不是荣耀的真实曲线。**
----------------------------
荣耀没有公开 Magic-Log 的编码参数，所以这里定义了一条 *结构上同构* 的替代曲线：
分段对数、低段线性、端到端单调，参数与 LogC3 相近但不同。它只用来
(a) 生成可控的测试素材，(b) 构造一个"输入假设明确"的还原 LUT，使整条
管线的正确性可以被逐项验证。

真实素材与真实 LUT 到位后，只需替换 ``.cube`` 文件，管线代码不用动。

三个结构性事实被显式建模，因为它们正是现实中的误差来源：

1. **两代曲线**（v1 / v2）：参数不同 → 用错版本的 LUT 会整体偏移。
2. **ISO 相关漂移**：编码值随 ISO 有小幅增益/偏置差 → 用单一 LUT 覆盖所有 ISO
   必然有残差（指导书 §1 的第一条不确定性）。
3. **编码域并非满量程**：曲线把线性 [0,1] 压进了一个子区间，超出的码值无意义。
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .cube import CurveLUT, LUT3D
from .transfers import rec709_oetf

__all__ = [
    "MagicLogCurve",
    "MAGICLOG_V1",
    "MAGICLOG_V2",
    "encode_with_iso",
    "make_restore_lut",
    "make_arri_lookup_lut",
]


@dataclass(frozen=True)
class MagicLogCurve:
    """分段对数编码曲线的参数（合成，非官方）。"""

    name: str
    a: float
    b: float
    c: float
    d: float
    cut: float
    f: float
    e: float = 0.0

    def __post_init__(self):
        if self.e == 0.0:
            # 由连续性条件反解低段斜率：e*cut + f = high(cut)
            hi = self.c * np.log10(self.a * self.cut + self.b) + self.d
            object.__setattr__(self, "e", float((hi - self.f) / self.cut))

    @property
    def cut_code(self) -> float:
        return self.e * self.cut + self.f

    def encode(self, x) -> np.ndarray:
        v = np.asarray(x, dtype=np.float32)
        safe = np.maximum(self.a * v + self.b, np.float32(1e-12))
        hi = np.float32(self.c) * np.log10(safe) + np.float32(self.d)
        lo = np.float32(self.e) * v + np.float32(self.f)
        return np.where(v > np.float32(self.cut), hi, lo).astype(np.float32, copy=False)

    def decode(self, y) -> np.ndarray:
        v = np.asarray(y, dtype=np.float32)
        safe = np.maximum((v - np.float32(self.d)) / np.float32(self.c), np.float32(-12.0))
        hi = (np.power(np.float32(10.0), safe) - np.float32(self.b)) / np.float32(self.a)
        lo = (v - np.float32(self.f)) / np.float32(self.e)
        return np.where(v > np.float32(self.cut_code), hi, lo).astype(np.float32, copy=False)

    def describe(self) -> dict:
        return {
            "name": self.name,
            "params": {"a": self.a, "b": self.b, "c": self.c, "d": self.d,
                       "cut": self.cut, "e": self.e, "f": self.f},
            "code_at_linear_0": float(self.encode(np.array([0.0]))[0]),
            "code_at_linear_0_18": float(self.encode(np.array([0.18]))[0]),
            "code_at_linear_1": float(self.encode(np.array([1.0]))[0]),
            "linear_at_code_1": float(self.decode(np.array([1.0]))[0]),
        }


# 合成参数：刻意与 LogC3 不同（c/d 都更小），但保持同一分段结构。
MAGICLOG_V2 = MagicLogCurve("synthetic-magiclog-v2", a=4.80, b=0.0600, c=0.2550, d=0.3800,
                            cut=0.01250, f=0.0850)
MAGICLOG_V1 = MagicLogCurve("synthetic-magiclog-v1", a=4.60, b=0.0680, c=0.2620, d=0.3720,
                            cut=0.01200, f=0.0800)


def encode_with_iso(linear, curve: MagicLogCurve = MAGICLOG_V2, iso: float = 100.0) -> np.ndarray:
    """带 ISO 漂移的编码（合成模型）。

    高 ISO 时传感器标定略有变化，表现为编码域的一点点增益与偏置：::

        y = curve.encode(x * gain(iso)) + offset(iso)
        gain(iso)   = 2 ** (0.035 * log2(iso/100))
        offset(iso) = 0.0040 * log2(iso/100)

    ISO 1600（4 档）时 gain≈1.10、offset≈+0.016（约 16 个 10-bit 码值）。
    """
    s = float(np.log2(max(iso, 1.0) / 100.0))
    gain = float(2.0 ** (0.035 * s))
    offset = float(0.0040 * s)
    return (curve.encode(np.asarray(linear, dtype=np.float32) * np.float32(gain))
            + np.float32(offset)).astype(np.float32, copy=False)


def make_restore_lut(curve: MagicLogCurve = MAGICLOG_V2, size: int = 33,
                     as_3d: bool = True):
    """构造"官方还原 LUT"的合成替身：Magic-Log 码值 -> Rec.709 显示码值。

    注意：输入是 **编码域** 归一化值（0..1 对应 10-bit 0..1023），
    输出是 **Rec.709 OETF 编码后** 的显示值——与真实还原 LUT 的惯例一致，
    也正是指导书 §2.3 要求先做 OETF 逆变换的原因。
    """
    grid = np.linspace(0.0, 1.0, size, dtype=np.float32)
    lin = np.clip(curve.decode(grid), 0.0, 1.0)
    disp = rec709_oetf(lin)
    if not as_3d:
        return CurveLUT(
            data=np.stack([disp, disp, disp], axis=-1),
            title=f"{curve.name} -> Rec.709 (synthetic restore LUT, 1D)",
        )
    r, g, b = np.meshgrid(grid, grid, grid, indexing="ij")
    # 曲线是逐通道可分的，因此格点值 = 对该通道码值做一次线性查表
    data = np.empty((size, size, size, 3), dtype=np.float32)
    for axis, coord in enumerate((r, g, b)):
        p = np.clip(coord, 0.0, 1.0) * np.float32(size - 1)
        j0 = np.clip(np.floor(p).astype(np.int32), 0, size - 2)
        ff = (p - j0).astype(np.float32)
        data[..., axis] = disp[j0] + (disp[j0 + 1] - disp[j0]) * ff
    return LUT3D(data=data, title=f"{curve.name} -> Rec.709 (synthetic restore LUT, 3D {size})")


def make_arri_lookup_lut(size: int = 33, look: str = "neutral", matrix=None) -> LUT3D:
    """构造 ARRI LogC3 -> Rec.709 的合成替身（输入 LogC3/AWG3，输出 709/SDR）。

    ``look="neutral"`` 时输出严格等于 ``rec709_oetf(AWG3->709(logc3_decode(p)))``，
    于是整条管线成为可解析对照的闭环，任何 ΔE 都归因于实现错误而非"风格"。
    ``look="arri_like"`` 时叠加一个轻微的电影感调色，仅用于生成演示图。

    ``matrix`` 可覆盖 AWG3->709 矩阵（默认由基色推导），用于矩阵来源的灵敏度实验。
    """
    grid = np.linspace(0.0, 1.0, size, dtype=np.float32)
    r, g, b = np.meshgrid(grid, grid, grid, indexing="ij")
    p = np.stack([r, g, b], axis=-1).astype(np.float32)
    lin_awg3 = np.clip(logc3_decode_safe(p), -1.0, 8.0)
    from .color import matrix_awg3_to_709

    M = (matrix_awg3_to_709() if matrix is None else np.asarray(matrix)).astype(np.float32)
    lin709 = lin_awg3 @ M.T
    if look == "arri_like":
        # 轻微 S 曲线 + 3% 饱和提升 + 高光柔化（纯演示用，非 ARRI 官方风格）
        x = np.clip(lin709, 0.0, 1.0)
        y = x * x * (3.0 - 2.0 * x) * 0.25 + x * 0.75
        lum = (0.2126 * y[..., 0] + 0.7152 * y[..., 1] + 0.0722 * y[..., 2])[..., None]
        y = lum + (y - lum) * 1.03
        lin709 = y
    data = rec709_oetf(np.clip(lin709, 0.0, 1.0))
    return LUT3D(data=data,
                 title=f"LogC3/AWG3 -> Rec.709 ({look}) synthetic {size}^3")


def logc3_decode_safe(p):
    from .transfers import logc3_decode

    return logc3_decode(p)
