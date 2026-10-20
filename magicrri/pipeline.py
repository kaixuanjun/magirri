# -*- coding: utf-8 -*-
"""主管线：Magic-Log ->（还原 LUT）-> Rec.709 ->（OETF 逆）-> 线性
->（709→AWG3）->（LogC3 编码）->（ARRI LUT）-> 输出。

与 ``glsl/logc3_pipeline.frag`` 逐阶段对应；每个阶段的中间结果都会返回，
便于定位问题（指导书 §4 要求的"先验证三个点"）。所有像素运算走 float32。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import numpy as np

from .color import matrix_709_to_awg3
from .transfers import logc3_encode, rec709_eotf, rec709_oetf_inverse

__all__ = ["PipelineConfig", "Pipeline", "quantize", "STAGES"]

STAGES = ["input", "restore", "linear_display", "linear_awg3", "logc3", "output"]


def quantize(x, bits: Optional[int]) -> np.ndarray:
    """把归一化浮点量化到 ``bits`` 位（模拟 8-bit/10-bit 采样）。"""
    v = np.asarray(x, dtype=np.float32)
    if not bits:
        return v
    top = float((1 << bits) - 1)
    return (np.rint(np.clip(v, 0.0, 1.0) * top) / top).astype(np.float32)


@dataclass
class PipelineConfig:
    """管线开关。默认值即指导书推荐的"正确"配置。"""

    restore_lut: Any
    look_lut: Any
    #: ``oetf_inverse``（正确）| ``gamma24``（错误：用 EOTF 近似）| ``none``（遗漏）
    decode_mode: str = "oetf_inverse"
    #: 是否在 LogC3 编码前做 Rec.709 -> AWG3 基色变换
    gamut_convert: bool = True
    interp: str = "tetrahedral"
    clip_output: bool = True
    input_bits: Optional[int] = None
    output_bits: Optional[int] = None
    #: 覆盖 709->AWG3 矩阵（默认由基色推导），用于"矩阵来源"灵敏度实验
    matrix_709_to_awg3: Optional[np.ndarray] = None
    #: 仅用于复现故障：把某个 LUT 的 R/B 轴读反
    perturb_restore_order: bool = False
    perturb_look_order: bool = False

    def describe(self) -> dict:
        return {
            "decode_mode": self.decode_mode,
            "gamut_convert": self.gamut_convert,
            "interp": self.interp,
            "clip_output": self.clip_output,
            "input_bits": self.input_bits,
            "output_bits": self.output_bits,
            "matrix_override": self.matrix_709_to_awg3 is not None,
            "perturb_restore_order": self.perturb_restore_order,
            "perturb_look_order": self.perturb_look_order,
            "restore_lut": self.restore_lut.describe(),
            "look_lut": self.look_lut.describe(),
        }


@dataclass
class Pipeline:
    cfg: PipelineConfig
    _m709_awg3: np.ndarray = field(init=False, repr=False)

    def __post_init__(self):
        m = self.cfg.matrix_709_to_awg3
        if m is None:
            m = matrix_709_to_awg3()
        self._m709_awg3 = np.asarray(m, dtype=np.float32)

    # -- 单阶段 ------------------------------------------------------------
    def decode_to_linear(self, display_code: np.ndarray) -> np.ndarray:
        mode = self.cfg.decode_mode
        if mode == "oetf_inverse":
            return rec709_oetf_inverse(display_code)
        if mode == "gamma24":
            return rec709_eotf(display_code)
        if mode == "none":
            return np.asarray(display_code, dtype=np.float32)
        raise ValueError(f"未知 decode_mode: {mode}")

    # -- 主管线 ------------------------------------------------------------
    def apply(self, log_rgb) -> Dict[str, np.ndarray]:
        """``log_rgb``: Magic-Log 编码值归一化到 [0,1]，形状 (...,3)。"""
        x = np.asarray(log_rgb, dtype=np.float32)
        x = quantize(x, self.cfg.input_bits)

        restore = self.cfg.restore_lut
        if self.cfg.perturb_restore_order and hasattr(restore, "permuted"):
            restore = restore.permuted()
        s_restore = restore.sample(x, self.cfg.interp)

        s_linear_display = self.decode_to_linear(s_restore)

        if self.cfg.gamut_convert:
            s_linear_awg3 = s_linear_display @ self._m709_awg3.T
        else:
            s_linear_awg3 = s_linear_display

        s_logc3 = logc3_encode(s_linear_awg3)

        look = self.cfg.look_lut
        if self.cfg.perturb_look_order and hasattr(look, "permuted"):
            look = look.permuted()
        out = look.sample(s_logc3, self.cfg.interp)

        if self.cfg.clip_output:
            out = np.clip(out, 0.0, 1.0)
        out = quantize(out, self.cfg.output_bits)
        return {
            "input": x,
            "restore": s_restore,
            "linear_display": s_linear_display,
            "linear_awg3": s_linear_awg3,
            "logc3": s_logc3,
            "output": out,
        }

    def __call__(self, log_rgb) -> np.ndarray:
        return self.apply(log_rgb)["output"]

    # -- 便利 --------------------------------------------------------------
    def stage_report(self, log_rgb) -> List[dict]:
        stages = self.apply(log_rgb)
        rows = []
        for name in STAGES:
            v = np.asarray(stages[name], dtype=np.float64).reshape(-1, 3)
            rows.append(
                {
                    "stage": name,
                    "min": float(v.min()),
                    "max": float(v.max()),
                    "mean": float(v.mean()),
                    "clipped_low": float((v <= 1e-6).mean()),
                    "clipped_high": float((v >= 1.0 - 1e-6).mean()),
                }
            )
        return rows
