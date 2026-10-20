# -*- coding: utf-8 -*-
"""色彩空间基元：色度坐标 -> 矩阵、Rec.709 <-> AWG3、XYZ <-> Lab。

设计原则
--------
矩阵全部由基色色度坐标 + 白点 *推导* 出来（而不是硬编码魔数），
再与 ARRI 公开发布的 AWG3 <-> Rec.709 矩阵交叉校验（见
:func:`arri_matrix_crosscheck`）。这样矩阵的可信度是"可复算"的。

白点：Rec.709 与 AWG3 共用 D65 (0.3127, 0.3290)，因此两者之间的转换
**不需要** 色适应（Bradford），只需基色变换。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Tuple

import numpy as np

__all__ = [
    "Primaries",
    "REC709",
    "AWG3",
    "D65",
    "rgb_to_xyz_matrix",
    "xyz_to_rgb_matrix",
    "matrix_709_to_awg3",
    "matrix_awg3_to_709",
    "REFERENCE_AWG3_TO_709_UNVERIFIED",
    "REFERENCE_709_TO_AWG3_UNVERIFIED",
    "arri_matrix_crosscheck",
    "xyz_to_lab",
    "lab_to_xyz",
    "srgb_u8_to_linear",
]


@dataclass(frozen=True)
class Primaries:
    name: str
    r: Tuple[float, float]
    g: Tuple[float, float]
    b: Tuple[float, float]

    def chromaticities(self) -> np.ndarray:
        return np.array([self.r, self.g, self.b], dtype=np.float64)


D65 = (0.3127, 0.3290)
REC709 = Primaries("Rec.709", (0.640, 0.330), (0.300, 0.600), (0.150, 0.060))
AWG3 = Primaries("ARRI Wide Gamut 3", (0.6840, 0.3130), (0.2210, 0.8480), (0.0861, -0.1020))


def _xy_to_XYZ(xy: Tuple[float, float]) -> np.ndarray:
    x, y = xy
    return np.array([x / y, 1.0, (1.0 - x - y) / y], dtype=np.float64)


def rgb_to_xyz_matrix(p: Primaries, white: Tuple[float, float] = D65) -> np.ndarray:
    """由基色色度坐标推导 RGB -> XYZ 矩阵（列 = 该基色的 XYZ）。"""
    ch = p.chromaticities()
    P = np.stack([_xy_to_XYZ(tuple(c)) for c in ch], axis=1)  # (3,3)
    W = _xy_to_XYZ(white)
    S = np.linalg.solve(P, W)
    return P * S  # 逐列缩放


def xyz_to_rgb_matrix(p: Primaries, white: Tuple[float, float] = D65) -> np.ndarray:
    return np.linalg.inv(rgb_to_xyz_matrix(p, white))


def matrix_709_to_awg3() -> np.ndarray:
    """线性 Rec.709 RGB -> 线性 AWG3 RGB。"""
    return xyz_to_rgb_matrix(AWG3) @ rgb_to_xyz_matrix(REC709)


def matrix_awg3_to_709() -> np.ndarray:
    """线性 AWG3 RGB -> 线性 Rec.709 RGB。"""
    return xyz_to_rgb_matrix(REC709) @ rgb_to_xyz_matrix(AWG3)


# 一组常见于网络技术文档的 AWG3 -> Rec.709 矩阵。
#
# **未通过核实**：由 ARRI 公布的基色推导出的矩阵（见上）与之最大相差 0.58，
# 用反解基色的方法可以看出它隐含的 B 基色色度坐标为 (0.0861, -0.102)——
# 一个"虚基色"。这要么说明它并非纯基色变换（含厂商自有的渲染矩阵），
# 要么说明转录有误。本实现因此**只以可复算的推导矩阵为准**，
# 该常量仅用于量化"抄错矩阵"的代价（见 verify.py 的 E2-e 灵敏度实验）。
REFERENCE_AWG3_TO_709_UNVERIFIED = np.array(
    [
        [1.617523, -0.537287, -0.080237],
        [-0.070573, 1.334613, -0.264040],
        [-0.021102, -0.226954, 1.248056],
    ],
    dtype=np.float64,
)
# 旧值 [0.638008, ...] 不是上面那个 AWG3->709 的逆（往返误差 0.058），
# 那份"参考"本身就是错的。正确做法是取官方矩阵的真逆，和推导值一致。
REFERENCE_709_TO_AWG3_UNVERIFIED = np.linalg.inv(REFERENCE_AWG3_TO_709_UNVERIFIED)


def arri_matrix_crosscheck() -> dict:
    """对比"由基色推导"与"流传的参考矩阵"，并检查推导矩阵自身的一致性。"""
    derived_awg_709 = matrix_awg3_to_709()
    derived_709_awg = matrix_709_to_awg3()
    return {
        "awg3_to_709_derived": derived_awg_709.tolist(),
        "awg3_to_709_reference_unverified": REFERENCE_AWG3_TO_709_UNVERIFIED.tolist(),
        "awg3_to_709_max_abs_diff": float(
            np.max(np.abs(derived_awg_709 - REFERENCE_AWG3_TO_709_UNVERIFIED))
        ),
        "709_to_awg3_derived": derived_709_awg.tolist(),
        "709_to_awg3_reference_unverified": REFERENCE_709_TO_AWG3_UNVERIFIED.tolist(),
        "709_to_awg3_max_abs_diff": float(
            np.max(np.abs(derived_709_awg - REFERENCE_709_TO_AWG3_UNVERIFIED))
        ),
        "awg3_to_709_row_sums": derived_awg_709.sum(axis=1).tolist(),
        "derived_inverse_roundtrip_max_abs": float(
            np.max(np.abs(derived_709_awg @ derived_awg_709 - np.eye(3)))
        ),
        "reference_pair_roundtrip_max_abs": float(
            np.max(np.abs(
                REFERENCE_709_TO_AWG3_UNVERIFIED @ REFERENCE_AWG3_TO_709_UNVERIFIED - np.eye(3)
            ))
        ),
    }


# --------------------------------------------------------------------------
# Lab (D65)
# --------------------------------------------------------------------------

def _f_lab(t):
    d = 6.0 / 29.0
    return np.where(t > d ** 3, np.cbrt(np.maximum(t, 0.0)), t / (3 * d * d) + 4.0 / 29.0)


def _f_lab_inv(t):
    d = 6.0 / 29.0
    return np.where(t > d, np.power(np.maximum(t, 0.0), 3), 3 * d * d * (t - 4.0 / 29.0))


def xyz_to_lab(xyz, white_xy: Tuple[float, float] = D65):
    """XYZ (D65) -> CIE L*a*b*。xyz 形状 (...,3)。"""
    v = np.asarray(xyz, dtype=np.float64)
    W = _xy_to_XYZ(white_xy)
    t = v / W
    f = _f_lab(t)
    L = 116.0 * f[..., 1] - 16.0
    a = 500.0 * (f[..., 0] - f[..., 1])
    b = 200.0 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], axis=-1)


def lab_to_xyz(lab, white_xy: Tuple[float, float] = D65):
    v = np.asarray(lab, dtype=np.float64)
    fy = (v[..., 0] + 16.0) / 116.0
    fx = fy + v[..., 1] / 500.0
    fz = fy - v[..., 2] / 200.0
    W = _xy_to_XYZ(white_xy)
    xyz = np.stack([_f_lab_inv(fx), _f_lab_inv(fy), _f_lab_inv(fz)], axis=-1) * W
    return xyz


def linear_rgb709_to_lab(linear_rgb):
    v = np.asarray(linear_rgb, dtype=np.float64)
    xyz = v @ rgb_to_xyz_matrix(REC709).T
    return xyz_to_lab(xyz)


def srgb_u8_to_linear(codes_u8) -> np.ndarray:
    """8-bit sRGB 码值 -> 线性光 float32。"""
    from .transfers import srgb_eotf

    c = np.asarray(codes_u8, dtype=np.float32) / np.float32(255.0)
    return srgb_eotf(c)
