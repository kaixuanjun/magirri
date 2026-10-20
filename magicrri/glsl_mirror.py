# -*- coding: utf-8 -*-
"""GLSL 管线的标量镜像（CPU 逐像素复算）。

目的：在没有 Android 设备 / GPU 的环境里，**逻辑上**证明
``glsl/logc3_pipeline.frag`` 的实现与 ``pipeline.py`` 等价。它不能替代
真机验证（驱动编译、精度限定符、纹理格式都还没验证），但能抓住
分段点写反、LUT 索引顺序、矩阵方向、插值分支这类致命错误。

实现刻意写成"一行一句、顺序与着色器相同"，并且只用 float32 标量运算。
"""

from __future__ import annotations

from typing import Any, Dict

import numpy as np

__all__ = [
    "rec709_oetf_inverse_f32",
    "logc3_encode_f32",
    "sample_lut3d_f32",
    "pipeline_pixel",
    "pipeline_image",
]


def _f(x) -> np.float32:
    return np.float32(x)


def rec709_oetf_inverse_f32(v: np.float32) -> np.float32:
    v = _f(v)
    if v < _f(0.081):
        return _f(v * _f(1.0 / 4.5))
    return _f(np.power(np.maximum(_f((v + _f(0.099)) * _f(1.0 / 1.099)), _f(0.0)), _f(1.0 / 0.45)))


def logc3_encode_f32(x: np.float32) -> np.float32:
    x = _f(x)
    if x > _f(0.010591):
        lg = _f(np.log10(np.float64(max(_f(5.555556) * x + _f(0.052272), _f(1e-12)))))
        lg = _f(lg * _f(0.247190))
        return _f(lg + _f(0.385537))
    return _f(_f(5.367655) * x + _f(0.092809))


def sample_lut3d_f32(data: np.ndarray, dom_min, dom_max, rgb, interp: str = "tetrahedral"):
    """与着色器 ``sampleLut3D`` 等价：``data[r][g][b]``，索引 0 在最小端。"""
    n = data.shape[0]
    dom_min = np.asarray(dom_min, dtype=np.float32)
    dom_max = np.asarray(dom_max, dtype=np.float32)
    span = np.maximum(dom_max - dom_min, _f(1e-8))
    q = np.clip((np.asarray(rgb, dtype=np.float32) - dom_min) / span, _f(0.0), _f(1.0))
    pos = q * _f(n - 1)
    i0 = np.clip(np.floor(pos).astype(np.int32), 0, n - 2)
    f = (pos - i0.astype(np.float32)).astype(np.float32)
    i, j, k = int(i0[0]), int(i0[1]), int(i0[2])
    c000 = data[i, j, k]
    c100 = data[i + 1, j, k]
    c010 = data[i, j + 1, k]
    c001 = data[i, j, k + 1]
    c110 = data[i + 1, j + 1, k]
    c101 = data[i + 1, j, k + 1]
    c011 = data[i, j + 1, k + 1]
    c111 = data[i + 1, j + 1, k + 1]
    fr, fg, fb = f[0], f[1], f[2]
    if interp == "trilinear":
        c00 = c000 + (c100 - c000) * fr
        c10 = c010 + (c110 - c010) * fr
        c01 = c001 + (c101 - c001) * fr
        c11 = c011 + (c111 - c011) * fr
        c0 = c00 + (c10 - c00) * fg
        c1 = c01 + (c11 - c01) * fg
        return c0 + (c1 - c0) * fb
    if fr > fg:
        if fg > fb:
            return c000 + (c100 - c000) * fr + (c110 - c100) * fg + (c111 - c110) * fb
        if fr > fb:
            return c000 + (c100 - c000) * fr + (c101 - c100) * fb + (c111 - c101) * fg
        return c000 + (c001 - c000) * fb + (c101 - c001) * fr + (c111 - c101) * fg
    if fb > fg:
        return c000 + (c001 - c000) * fb + (c011 - c001) * fg + (c111 - c011) * fr
    if fb > fr:
        return c000 + (c010 - c000) * fg + (c011 - c010) * fb + (c111 - c011) * fr
    return c000 + (c010 - c000) * fg + (c110 - c010) * fr + (c111 - c110) * fb


def _sample(lut, rgb, interp):
    """按 LUT 类型分派：3D 用四面体/三线性，1D 用逐通道线性。"""
    from .cube import CurveLUT

    if isinstance(lut, CurveLUT):
        n = lut.size
        pos = np.clip((rgb - lut.domain_min) / np.maximum(lut.domain_max - lut.domain_min, 1e-8),
                      0.0, 1.0) * np.float32(n - 1)
        i0 = np.clip(np.floor(pos).astype(np.int32), 0, n - 2)
        f = (pos - i0).astype(np.float32)
        out = np.empty(3, dtype=np.float32)
        for ch in range(3):
            lo = lut.data[i0[ch], ch]
            hi = lut.data[i0[ch] + 1, ch]
            out[ch] = lo + (hi - lo) * f[ch]
        return out
    return sample_lut3d_f32(lut.data, lut.domain_min, lut.domain_max, rgb, interp)


def pipeline_pixel(cfg: Dict[str, Any], rgb) -> np.ndarray:
    """单像素运行整条着色器逻辑。``cfg`` 见 :func:`pipeline_image`。"""
    log_color = np.asarray(rgb, dtype=np.float32)
    display709 = _sample(cfg["restore_lut"], log_color, cfg["interp"])

    mode = cfg["decode_mode"]
    if mode == "oetf":
        lin709 = np.array([rec709_oetf_inverse_f32(display709[0]),
                           rec709_oetf_inverse_f32(display709[1]),
                           rec709_oetf_inverse_f32(display709[2])], dtype=np.float32)
    elif mode == "gamma24":
        lin709 = np.power(np.maximum(display709, 0.0), _f(2.4)).astype(np.float32)
    else:
        lin709 = display709.astype(np.float32)

    if cfg["gamut_convert"]:
        M = cfg["m709_to_awg3"].astype(np.float32)
        lin_awg3 = np.array([M[r, 0] * lin709[0] + M[r, 1] * lin709[1] + M[r, 2] * lin709[2]
                             for r in range(3)], dtype=np.float32)
    else:
        lin_awg3 = lin709

    logc3 = np.array([logc3_encode_f32(lin_awg3[0]),
                      logc3_encode_f32(lin_awg3[1]),
                      logc3_encode_f32(lin_awg3[2])], dtype=np.float32)

    out = _sample(cfg["look_lut"], logc3, cfg["interp"])
    if cfg["clip_output"]:
        out = np.clip(out, 0.0, 1.0)
    return np.asarray(out, dtype=np.float32)


def pipeline_image(cfg: Dict[str, Any], log_image) -> np.ndarray:
    """对 (H,W,3) 或 (N,3) 图像逐像素跑 :func:`pipeline_pixel`。"""
    img = np.asarray(log_image, dtype=np.float32)
    flat = img.reshape(-1, 3)
    out = np.empty_like(flat)
    for i in range(flat.shape[0]):
        out[i] = pipeline_pixel(cfg, flat[i])
    return out.reshape(img.shape)
