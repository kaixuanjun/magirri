# -*- coding: utf-8 -*-
"""Iridas/Resolve ``.cube`` 解析与 3D LUT 采样（三线性 / 四面体）。

**顺序约定（重要）**
--------------------
Iridas ``.cube`` 的扁平数据是 **红色变化最快**（B 变化最慢）::

    entry k  ->  r_index = k % N
                 g_index = (k // N) % N
                 b_index = k // (N*N)

官方规范里最小的 2x2x2 示例即::

    LUT_3D_SIZE 2
    0 0 0      <- (r=0,g=0,b=0)
    1 0 0      <- (r=1,g=0,b=0)
    0 1 0
    1 1 0
    0 0 1
    1 0 1
    0 1 1
    1 1 1

即第 2 行是 R 满、G/B 为 0。开发指导书 §3.2 写的是「R 变化最慢、B 变化最快」，
与此相反。选错一个的后果是 LUT 在内存里被「转置」，等价于 **R 与 B 通道互换**：
在色卡上实测平均 ΔE 约 21，表现为系统性的品红/黄绿偏色——不像噪点那样显眼，
很容易被误判成「这个 LUT 本来就这样」。所以本模块提供 :meth:`LUT3D.permuted`
用于复现该故障，并有回归测试钉死顺序（见验证报告 E2）。

内部存储统一为 ``data[r, g, b, 3]``，float32。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import List, Sequence, Tuple

import numpy as np

__all__ = ["LUT3D", "CurveLUT", "parse_cube", "read_cube"]


_FLOAT_RE = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def _numbers(line: str) -> List[float]:
    return [float(t) for t in _FLOAT_RE.findall(line)]


class _Sampler:
    """采样器公共接口：``sample(rgb) -> rgb``，形状 (...,3)。"""

    size: int
    domain_min: np.ndarray
    domain_max: np.ndarray

    def _normalize(self, rgb) -> np.ndarray:
        v = np.asarray(rgb, dtype=np.float32)
        span = (self.domain_max - self.domain_min).astype(np.float32)
        span = np.where(span == 0, np.float32(1.0), span)
        q = (v - self.domain_min.astype(np.float32)) / span
        return np.clip(q, np.float32(0.0), np.float32(1.0))


@dataclass
class LUT3D(_Sampler):
    """3D LUT。``data`` 形状 ``(N, N, N, 3)``，索引顺序 ``[r, g, b]``。"""

    data: np.ndarray
    domain_min: np.ndarray = field(default_factory=lambda: np.zeros(3, np.float32))
    domain_max: np.ndarray = field(default_factory=lambda: np.ones(3, np.float32))
    title: str = ""
    source: str = ""
    order: str = "red-fastest"
    input_range_3d: Tuple[float, float] | None = None

    def __post_init__(self):
        self.data = np.ascontiguousarray(self.data, dtype=np.float32)
        if self.data.ndim != 4 or self.data.shape[3] != 3:
            raise ValueError(f"LUT3D data 形状应为 (N,N,N,3)，实际 {self.data.shape}")
        n = self.data.shape[0]
        if self.data.shape[1] != n or self.data.shape[2] != n:
            raise ValueError("LUT3D 三个轴长度必须一致")
        self.domain_min = np.asarray(self.domain_min, dtype=np.float32).reshape(3)
        self.domain_max = np.asarray(self.domain_max, dtype=np.float32).reshape(3)

    # -- 构造 --------------------------------------------------------------
    @property
    def size(self) -> int:
        return int(self.data.shape[0])

    @classmethod
    def identity(cls, size: int = 33) -> "LUT3D":
        g = np.linspace(0.0, 1.0, size, dtype=np.float32)
        r, gg, b = np.meshgrid(g, g, g, indexing="ij")
        return cls(data=np.stack([r, gg, b], axis=-1))

    def permuted(self) -> "LUT3D":
        """模拟"顺序读反"的故障：交换 R 与 B 轴。"""
        return LUT3D(
            data=np.transpose(self.data, (2, 1, 0, 3)).copy(),
            domain_min=self.domain_min,
            domain_max=self.domain_max,
            title=self.title + " [axis-permuted]",
            source=self.source,
            order="blue-fastest",
            input_range_3d=self.input_range_3d,
        )

    # -- 采样 --------------------------------------------------------------
    def sample(self, rgb, interp: str = "tetrahedral") -> np.ndarray:
        """在 LUT 上采样。``interp`` ∈ {``tetrahedral``, ``trilinear``, ``nearest``}。"""
        q = self._normalize(rgb)
        n = self.size
        if n == 1:
            return np.broadcast_to(self.data[0, 0, 0], np.asarray(rgb).shape).astype(np.float32)
        pos = q * np.float32(n - 1)
        i0 = np.floor(pos)
        i0 = np.clip(i0, 0, n - 2).astype(np.int32)
        f = (pos - i0).astype(np.float32)
        ir, ig, ib = i0[..., 0], i0[..., 1], i0[..., 2]
        d = self.data
        c000 = d[ir, ig, ib]
        c100 = d[ir + 1, ig, ib]
        c010 = d[ir, ig + 1, ib]
        c001 = d[ir, ig, ib + 1]
        c110 = d[ir + 1, ig + 1, ib]
        c101 = d[ir + 1, ig, ib + 1]
        c011 = d[ir, ig + 1, ib + 1]
        c111 = d[ir + 1, ig + 1, ib + 1]
        if interp == "nearest":
            irn = np.clip(np.rint(pos[..., 0]).astype(np.int32), 0, n - 1)
            ign = np.clip(np.rint(pos[..., 1]).astype(np.int32), 0, n - 1)
            ibn = np.clip(np.rint(pos[..., 2]).astype(np.int32), 0, n - 1)
            return d[irn, ign, ibn].astype(np.float32, copy=False)
        if interp == "trilinear":
            fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]
            c00 = c000 * (1 - fr) + c100 * fr
            c10 = c010 * (1 - fr) + c110 * fr
            c01 = c001 * (1 - fr) + c101 * fr
            c11 = c011 * (1 - fr) + c111 * fr
            c0 = c00 * (1 - fg) + c10 * fg
            c1 = c01 * (1 - fg) + c11 * fg
            return (c0 * (1 - fb) + c1 * fb).astype(np.float32, copy=False)
        if interp != "tetrahedral":
            raise ValueError(f"未知插值方式: {interp}")
        # 四面体插值：6 种排序，与 Kang/Resolve/OCIO 的标准实现一致。
        # 分支结构与下面的伪代码严格对应（含等值时的落位）：
        #   if (r > g) { if (g > b) 0 else if (r > b) 1 else 2 }
        #   else       { if (b > g) 3 else if (b > r) 4 else 5 }
        fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]
        r_gt_g = fr > fg
        g_gt_b = fg > fb
        r_gt_b = fr > fb
        b_gt_g = fb > fg
        b_gt_r = fb > fr
        # 分支结构与标量实现（GLSL / glsl_mirror）严格一一对应：
        #   if (r > g) { if (g > b) 0 else if (r > b) 1 else 2 }
        #   else       { if (b > g) 3 else if (b > r) 4 else 5 }
        m0 = r_gt_g & g_gt_b                    # r > g > b
        m1 = r_gt_g & ~g_gt_b & r_gt_b          # r > b >= g
        m2 = r_gt_g & ~g_gt_b & ~r_gt_b         # b >= r > g
        m3 = ~r_gt_g & b_gt_g                   # b > g >= r
        m4 = ~r_gt_g & ~b_gt_g & b_gt_r         # g >= b > r
        cases = (
            (m0, c000 + fr * (c100 - c000) + fg * (c110 - c100) + fb * (c111 - c110)),
            (m1, c000 + fr * (c100 - c000) + fb * (c101 - c100) + fg * (c111 - c101)),
            (m2, c000 + fb * (c001 - c000) + fr * (c101 - c001) + fg * (c111 - c101)),
            (m3, c000 + fb * (c001 - c000) + fg * (c011 - c001) + fr * (c111 - c011)),
            (m4, c000 + fg * (c010 - c000) + fb * (c011 - c010) + fr * (c111 - c011)),
        )
        out = np.empty_like(c000)
        # 默认分支：g >= r >= b
        np.copyto(out, c000 + fg * (c010 - c000) + fr * (c110 - c010) + fb * (c111 - c110))
        for mask, val in cases:
            np.copyto(out, val, where=np.broadcast_to(mask, out.shape))
        return out.astype(np.float32, copy=False)

    __call__ = sample

    # -- 元信息 ------------------------------------------------------------
    def describe(self) -> dict:
        g = np.linspace(0.0, 1.0, self.size, dtype=np.float32)
        gray = np.stack([g] * 3, axis=-1)
        along = self.sample(gray)
        return {
            "title": self.title,
            "size": self.size,
            "domain_min": self.domain_min.tolist(),
            "domain_max": self.domain_max.tolist(),
            "order": self.order,
            "input_range_3d": self.input_range_3d,
            "source": self.source,
            # 灰轴诊断：三通道互差大 => 自带色彩倾向，或轴读反了
            "gray_axis_channel_spread": float(
                np.max(np.abs(along - along.mean(axis=1, keepdims=True)))
            ),
            "gray_axis_mean_shift": float(np.mean(along.mean(axis=1) - g)),
        }


@dataclass
class CurveLUT(_Sampler):
    """1D LUT（每通道一条曲线），形状 ``(N, 3)``。"""

    data: np.ndarray
    domain_min: np.ndarray = field(default_factory=lambda: np.zeros(3, np.float32))
    domain_max: np.ndarray = field(default_factory=lambda: np.ones(3, np.float32))
    title: str = ""
    source: str = ""
    order: str = "1d"

    def __post_init__(self):
        self.data = np.ascontiguousarray(self.data, dtype=np.float32)
        if self.data.ndim != 2 or self.data.shape[1] != 3:
            raise ValueError(f"CurveLUT data 形状应为 (N,3)，实际 {self.data.shape}")
        self.domain_min = np.asarray(self.domain_min, dtype=np.float32).reshape(3)
        self.domain_max = np.asarray(self.domain_max, dtype=np.float32).reshape(3)

    @property
    def size(self) -> int:
        return int(self.data.shape[0])

    def to_lut3d(self, size: int = 33) -> LUT3D:
        """把 1D 曲线提升为可采样 3D LUT（用于统一管线接口）。"""
        g = np.linspace(0.0, 1.0, size, dtype=np.float32)
        r, gg, b = np.meshgrid(g, g, g, indexing="ij")
        rgb = np.stack([r, gg, b], axis=-1)
        return LUT3D(
            data=self.sample(rgb, "trilinear"),
            domain_min=self.domain_min,
            domain_max=self.domain_max,
            title=self.title,
            source=self.source,
            order="1d->3d",
        )

    def sample(self, rgb, interp: str = "trilinear") -> np.ndarray:
        q = self._normalize(rgb)
        n = self.size
        if n == 1:
            return np.broadcast_to(self.data[0], np.asarray(rgb).shape).astype(np.float32)
        pos = q * np.float32(n - 1)
        i0 = np.clip(np.floor(pos), 0, n - 2).astype(np.int32)
        f = pos - i0
        out = np.empty(np.asarray(rgb).shape, dtype=np.float32)
        for ch in range(3):
            lo = self.data[i0[..., ch], ch]
            hi = self.data[i0[..., ch] + 1, ch]
            out[..., ch] = lo + (hi - lo) * f[..., ch]
        return out

    __call__ = sample

    def describe(self) -> dict:
        return {
            "title": self.title,
            "size": self.size,
            "kind": "1D",
            "domain_min": self.domain_min.tolist(),
            "domain_max": self.domain_max.tolist(),
            "source": self.source,
        }


# --------------------------------------------------------------------------
# 解析
# --------------------------------------------------------------------------

def _tail_int(line: str, keyword: str) -> int:
    """取关键字**之后**的第一个整数。

    不能直接对整行做数字提取：``LUT_3D_SIZE`` 里的 "3" 会被误当成尺寸，
    这是自己写解析器时最常见的坑（本仓库的历史 bug 之一）。
    """
    tail = line[len(keyword):].strip()
    nums = _numbers(tail)
    if not nums:
        raise ValueError(f"{keyword} 之后缺少数值: {line!r}")
    return int(round(nums[0]))

def parse_cube(text: str, source: str = "") -> "_Sampler":
    """解析 ``.cube`` 文本，返回 :class:`LUT3D` 或 :class:`CurveLUT`。

    支持 ``TITLE``、``LUT_3D_SIZE``、``LUT_1D_SIZE``、``DOMAIN_MIN``、
    ``DOMAIN_MAX``、``LUT_3D_INPUT_RANGE``、``LUT_1D_INPUT_RANGE``、``#`` 注释。
    ``DOMAIN_MIN/MAX`` 一律按文件头解析，不硬编码 [0,1]。
    """
    size_3d = None
    size_1d = None
    domain_min = None
    domain_max = None
    input_range = None
    title = ""
    values: List[float] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        upper = line.upper()
        if upper.startswith("TITLE"):
            m = re.search(r'"([^"]*)"', line)
            title = m.group(1) if m else line[5:].strip()
            continue
        if upper.startswith("LUT_3D_SIZE"):
            size_3d = _tail_int(line, "LUT_3D_SIZE")
            continue
        if upper.startswith("LUT_1D_SIZE"):
            size_1d = _tail_int(line, "LUT_1D_SIZE")
            continue
        if upper.startswith("LUT_3D_INPUT_RANGE"):
            nums = _numbers(line)
            input_range = (float(nums[0]), float(nums[-1]))
            continue
        if upper.startswith("LUT_1D_INPUT_RANGE"):
            nums = _numbers(line)
            input_range = (float(nums[0]), float(nums[-1]))
            continue
        if upper.startswith("DOMAIN_MIN"):
            domain_min = np.array(_numbers(line), dtype=np.float32)
            continue
        if upper.startswith("DOMAIN_MAX"):
            domain_max = np.array(_numbers(line), dtype=np.float32)
            continue
        if line[0].isdigit() or line[0] in "+-.":
            values.extend(_numbers(line))
            continue
        raise ValueError(f"无法识别的 .cube 行: {line!r}")

    if size_3d is None and size_1d is None:
        raise ValueError("缺少 LUT_3D_SIZE / LUT_1D_SIZE")
    if size_3d is not None and size_1d is not None:
        raise ValueError("同时出现 LUT_3D_SIZE 与 LUT_1D_SIZE，文件可能已合并")

    n = size_3d if size_3d is not None else size_1d
    expected = n ** 3 if size_3d is not None else n
    if len(values) != expected * 3:
        raise ValueError(
            f"数据量不匹配: 期望 {expected} 行 x3 值, 实际 {len(values)} 个浮点数"
        )
    arr = np.array(values, dtype=np.float32).reshape(expected, 3)

    if domain_min is None or domain_max is None:
        lo, hi = input_range if input_range else (0.0, 1.0)
        domain_min = np.full(3, lo, dtype=np.float32) if domain_min is None else domain_min
        domain_max = np.full(3, hi, dtype=np.float32) if domain_max is None else domain_max
    if domain_min.shape != (3,) or domain_max.shape != (3,):
        raise ValueError("DOMAIN_MIN / DOMAIN_MAX 必须各 3 个数")

    if size_3d is not None:
        # 红变化最快 -> reshape 成 (b, g, r, 3) 再转置为 [r, g, b]
        cube = arr.reshape(n, n, n, 3).transpose(2, 1, 0, 3)
        return LUT3D(
            data=cube,
            domain_min=domain_min,
            domain_max=domain_max,
            title=title,
            source=source,
            input_range_3d=input_range,
        )
    return CurveLUT(
        data=arr,
        domain_min=domain_min,
        domain_max=domain_max,
        title=title,
        source=source,
    )


def read_cube(path: str) -> "_Sampler":
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return parse_cube(fh.read(), source=str(path))


def write_cube(lut: "_Sampler", path: str, title: str | None = None) -> None:
    """把 LUT 写成 ``.cube``（红色变化最快，与解析器互逆）。"""
    if isinstance(lut, CurveLUT):
        lines = ["# generated by magicrri", f'TITLE "{title or lut.title or "curve"}"',
                 f"LUT_1D_SIZE {lut.size}"]
        _append_domain(lines, lut)
        for row in lut.data:
            lines.append(" ".join(f"{v:.8f}" for v in row))
    else:
        lines = ["# generated by magicrri", f'TITLE "{title or lut.title or "lut"}"',
                 f"LUT_3D_SIZE {lut.size}"]
        _append_domain(lines, lut)
        flat = lut.data.transpose(2, 1, 0, 3).reshape(-1, 3)  # r 最快
        for row in flat:
            lines.append(" ".join(f"{v:.8f}" for v in row))
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def _append_domain(lines: List[str], lut) -> None:
    lines.append("DOMAIN_MIN " + " ".join(f"{v:.8f}" for v in lut.domain_min))
    lines.append("DOMAIN_MAX " + " ".join(f"{v:.8f}" for v in lut.domain_max))
