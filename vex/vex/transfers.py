# -*- coding: utf-8 -*-
"""传递函数：ARRI LogC3 (EI 800) 与 Rec.709 OETF。

本模块是整条管线的数学地基，所有公式都与 GLSL 版本
(``glsl/logc3_pipeline.frag``) 逐行对应，误差只来自浮点精度。

约定
----
* 像素处理路径统一 ``float32``（与 GPU 一致）；参考/验证路径用 ``float64``。
* 所有函数对 numpy 数组逐元素工作，也可以接收 Python 标量。
* LogC3 的编码值范围并非 [0, 1]：线性 0.0 编码为 0.0928，线性 1.0 编码为
  0.5706。这一点对 3D LUT 的采样域非常关键（见 :func:`logc3_code_range`）。

参考
----
ARRI 公开的 LogC3 (EI 800) 参数::

    cut = 0.010591   a = 5.555556   b = 0.052272   c = 0.247190
    d   = 0.385537   e = 5.367655   f = 0.092809

编码 ``y = c*log10(a*x + b) + d`` (x > cut)，否则 ``y = e*x + f``。
ITU-R BT.709 OETF ``V = 4.5L`` (L < 0.018)，否则 ``V = 1.099*L**0.45 - 0.099``。
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

__all__ = [
    "LogC3Params",
    "LOGC3_EI800",
    "rec709_oetf",
    "rec709_oetf_inverse",
    "rec709_eotf",
    "logc3_encode",
    "logc3_decode",
    "logc3_code_range",
    "srgb_eotf",
    "srgb_eotf_inverse",
]


# --------------------------------------------------------------------------
# LogC3
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class LogC3Params:
    """一组 LogC3 编码参数（含分段点）。"""

    cut: float = 0.010591
    a: float = 5.555556
    b: float = 0.052272
    c: float = 0.247190
    d: float = 0.385537
    e: float = 5.367655
    f: float = 0.092809

    # -- 派生量 ------------------------------------------------------------
    @property
    def cut_code(self) -> float:
        """分段点在编码域的位置，即 ``e*cut + f``（约 0.1497）。"""
        return self.e * self.cut + self.f

    def continuity_error(self) -> float:
        """两段曲线在分段点处的跳变（用于自检，应为 ~1e-6 量级）。"""
        x = self.cut
        hi = self.c * np.log10(self.a * x + self.b) + self.d
        lo = self.e * x + self.f
        return float(abs(hi - lo))


LOGC3_EI800 = LogC3Params()


def logc3_encode(linear, params: LogC3Params = LOGC3_EI800):
    """线性光 -> LogC3 编码值（float32 安全）。

    ``linear`` 允许为负（LogC3 在 0 以下仍有定义），也允许 >1（线性域外延），
    但 >1 的输出会超过 0.5706，超出典型 3D LUT 的采样域，需调用方自行裁剪。
    """
    x = np.asarray(linear, dtype=np.float32)
    p = params
    # np.where 两个分支都会求值，log10 对非正数会产生 nan/warning，故先做安全钳位。
    safe = np.maximum(p.a * x + p.b, np.float32(1e-12))
    log_branch = np.float32(p.c) * np.log10(safe) + np.float32(p.d)
    lin_branch = np.float32(p.e) * x + np.float32(p.f)
    return np.where(x > np.float32(p.cut), log_branch, lin_branch).astype(np.float32, copy=False)


def logc3_decode(code, params: LogC3Params = LOGC3_EI800):
    """LogC3 编码值 -> 线性光。"""
    y = np.asarray(code, dtype=np.float32)
    p = params
    safe = np.maximum((y - np.float32(p.d)) / np.float32(p.c), np.float32(-12.0))
    log_branch = (np.power(np.float32(10.0), safe) - np.float32(p.b)) / np.float32(p.a)
    lin_branch = (y - np.float32(p.f)) / np.float32(p.e)
    return np.where(y > np.float32(p.cut_code), log_branch, lin_branch).astype(np.float32, copy=False)


def logc3_code_range(params: LogC3Params = LOGC3_EI800) -> dict:
    """返回 LogC3 关键点的编码/线性对应值，供报告引用。"""
    p = params
    out = {
        "cut_code": p.cut_code,
        "code_at_linear_0": float(logc3_encode(np.array([0.0]), p)[0]),
        "code_at_linear_0_18": float(logc3_encode(np.array([0.18]), p)[0]),
        "code_at_linear_1": float(logc3_encode(np.array([1.0]), p)[0]),
        "linear_at_code_0": float(logc3_decode(np.array([0.0]), p)[0]),
        "linear_at_code_1": float(logc3_decode(np.array([1.0]), p)[0]),
    }
    # 原生 LogC3 的码值 1.0 对应的线性值 ≈ 55.1，即比 18% 灰高 8.26 档
    out["stops_above_white_at_code_1"] = float(np.log2(out["linear_at_code_1"] / 1.0))
    out["stops_from_mid_to_code_1"] = float(np.log2(out["linear_at_code_1"] / 0.18))
    # 本管线（经 Rec.709 中间步骤）能触到的最高码值，及其相对原生 LogC3 的损失
    out["pipeline_max_code"] = out["code_at_linear_1"]
    out["pipeline_lost_stops_at_top"] = float(np.log2(out["linear_at_code_1"] / 1.0))
    return out


# --------------------------------------------------------------------------
# Rec.709
# --------------------------------------------------------------------------

_R709_ALPHA = 1.099
_R709_BETA = 0.099
_R709_GAMMA = 0.45
_R709_SLOPE = 4.5
_R709_KNEE_LIN = 0.018
_R709_KNEE_CODE = _R709_SLOPE * _R709_KNEE_LIN  # 0.081


def rec709_oetf(linear):
    """线性光 -> Rec.709 OETF 显示编码值 (0..1)。"""
    x = np.asarray(linear, dtype=np.float32)
    hi = np.float32(_R709_ALPHA) * np.power(np.maximum(x, np.float32(0.0)), np.float32(_R709_GAMMA)) \
        - np.float32(_R709_BETA)
    lo = np.float32(_R709_SLOPE) * x
    return np.where(x < np.float32(_R709_KNEE_LIN), lo, hi).astype(np.float32, copy=False)


def rec709_oetf_inverse(v):
    """Rec.709 OETF 显示编码值 -> 线性光。

    这是指导书 §2.3 强调的、最容易被遗漏的一步。分段点 0.081 对应线性 0.018。
    负值按线性段外延（保留 LogC3 的负半轴信息）。
    """
    y = np.asarray(v, dtype=np.float32)
    hi = np.power(
        np.maximum((y + np.float32(_R709_BETA)) / np.float32(_R709_ALPHA), np.float32(0.0)),
        np.float32(1.0 / _R709_GAMMA),
    )
    lo = y / np.float32(_R709_SLOPE)
    return np.where(y < np.float32(_R709_KNEE_CODE), lo, hi).astype(np.float32, copy=False)


def rec709_eotf(v):
    """BT.709 显示 EOTF（gamma 2.4 形式），仅用于对照实验。

    真实显示器的 EOTF 是 ``L = (V + 0.099)/1.099) ** (1/0.45)`` 的近似替代品；
    与 :func:`rec709_oetf_inverse` 相比，两者在中高调差异可达 1~3 个 ΔE，
    见 ``verify.py`` 的 E2 消融实验。
    """
    y = np.asarray(v, dtype=np.float32)
    return np.power(np.maximum(y, np.float32(0.0)), np.float32(2.4)).astype(np.float32, copy=False)


# --------------------------------------------------------------------------
# sRGB (仅用于参考色卡的 8-bit 值解码)
# --------------------------------------------------------------------------

def srgb_eotf_inverse(linear):
    """线性 -> sRGB 编码。"""
    x = np.asarray(linear, dtype=np.float32)
    return np.where(
        x <= np.float32(0.0031308),
        np.float32(12.92) * x,
        np.float32(1.055) * np.power(np.maximum(x, np.float32(0.0)), np.float32(1 / 2.4))
        - np.float32(0.055),
    ).astype(np.float32, copy=False)


def srgb_eotf(code):
    """sRGB 编码 -> 线性。"""
    y = np.asarray(code, dtype=np.float32)
    return np.where(
        y <= np.float32(0.04045),
        y / np.float32(12.92),
        np.power(np.maximum((y + np.float32(0.055)) / np.float32(1.055), np.float32(0.0)),
                 np.float32(2.4)),
    ).astype(np.float32, copy=False)
