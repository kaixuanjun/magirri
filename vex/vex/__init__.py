# -*- coding: utf-8 -*-
"""magicrri —— Magic-Log -> LogC3 实时映射管线的桌面参考实现。

模块划分
--------
``transfers``    LogC3 (EI800) 编码/解码、Rec.709 OETF 及其逆
``color``        色度坐标 -> 矩阵推导、Rec.709 <-> AWG3、Lab
``cube``         ``.cube`` 解析/写出、3D LUT 四面体/三线性采样
``curve``        合成"类 Magic-Log"曲线、还原 LUT 与 ARRI LUT 的合成替身
``fixtures``     ColorChecker 参考色块、阶跃楔、色块图渲染
``pipeline``     五阶段主管线（与 GLSL 逐行对应）
``glsl_mirror``  着色器逻辑的 CPU 标量镜像，用于等价性校验
``metrics``      CIEDE2000、色阶断裂、裁剪统计
``verify``       全部实验 + 报告生成

快速开始
--------
    python -m vex verify --out report
"""

from __future__ import annotations

__version__ = "0.1.0"

from . import color, cube, curve, fixtures, glsl_mirror, metrics, pipeline, transfers  # noqa: F401

__all__ = [
    "__version__",
    "transfers",
    "color",
    "cube",
    "curve",
    "fixtures",
    "pipeline",
    "glsl_mirror",
    "metrics",
]
