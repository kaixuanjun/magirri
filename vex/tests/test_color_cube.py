# -*- coding: utf-8 -*-
"""色彩空间、.cube 解析与 LUT 采样测试。"""

from __future__ import annotations

import os
import tempfile
import unittest

import numpy as np

from vex import color
from vex.cube import CurveLUT, LUT3D, parse_cube, read_cube, write_cube
from vex.metrics import code_occupancy


class TestColor(unittest.TestCase):
    def test_white_preserved(self):
        m = color.matrix_709_to_awg3()
        np.testing.assert_allclose(m.sum(axis=1), np.ones(3), atol=1e-12)
        m2 = color.matrix_awg3_to_709()
        np.testing.assert_allclose(m2.sum(axis=1), np.ones(3), atol=1e-12)

    def test_inverse_pair(self):
        ck = color.arri_matrix_crosscheck()
        self.assertLess(ck["derived_inverse_roundtrip_max_abs"], 1e-12)

    def test_lab_roundtrip(self):
        lab = np.array([[50.0, 20.0, -30.0], [80.0, -5.0, 12.0], [10.0, 0.0, 0.0]])
        back = color.xyz_to_lab(color.lab_to_xyz(lab))
        np.testing.assert_allclose(back, lab, atol=1e-9)

    def test_matches_arri_published_matrix(self):
        """由基色推导的矩阵必须与 ARRI 官方公开的 AWG3→Rec.709 矩阵一致。

        这条曾经写反过：早期版本用错误的 AWG3 基色（绿 y=0.6740、蓝 y=0.1540），
        推导结果和官方差 0.4，测试却断言"必须差 >0.1"，等于把 bug 锁死了。
        现在反过来：必须一致，容差 1e-5。
        """
        ck = color.arri_matrix_crosscheck()
        self.assertLess(ck["awg3_to_709_max_abs_diff"], 1e-5)
        self.assertLess(ck["709_to_awg3_max_abs_diff"], 1e-5)

    def test_709_primaries_stay_in_gamut(self):
        """Rec.709 的色域完全包含在 AWG3 内，转换后每个通道都必须非负。

        出现负值就意味着饱和色会被逐通道裁掉，蓝色会变成紫色。
        """
        m = color.matrix_709_to_awg3()
        for name, rgb in (("R", (1, 0, 0)), ("G", (0, 1, 0)), ("B", (0, 0, 1))):
            v = m @ np.array(rgb, dtype=np.float64)
            self.assertGreaterEqual(v.min(), 0.0, f"709 {name} 在 AWG3 里出现了负通道: {v}")


CANONICAL_2x2x2 = """
# 官方规范里的最小示例：红色变化最快
LUT_3D_SIZE 2
0.0 0.0 0.0
1.0 0.0 0.0
0.0 1.0 0.0
1.0 1.0 0.0
0.0 0.0 1.0
1.0 0.0 1.0
0.0 1.0 1.0
1.0 1.0 1.0
"""


class TestCube(unittest.TestCase):
    def test_order_is_red_fastest(self):
        lut = parse_cube(CANONICAL_2x2x2)
        # 第 2 行是 (1,0,0)：R 满、G/B 为 0  -> data[1,0,0]
        np.testing.assert_allclose(lut.data[1, 0, 0], [1.0, 0.0, 0.0])
        np.testing.assert_allclose(lut.data[0, 1, 0], [0.0, 1.0, 0.0])
        np.testing.assert_allclose(lut.data[0, 0, 1], [0.0, 0.0, 1.0])

    def test_roundtrip_write_read(self):
        rng = np.random.default_rng(7)
        data = rng.random((9, 9, 9, 3), dtype=np.float32)
        lut = LUT3D(data=data, title="random")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.cube")
            write_cube(lut, p)
            back = read_cube(p)
        np.testing.assert_allclose(back.data, data, atol=1e-6)

    def test_identity_interpolation_exact(self):
        lut = LUT3D.identity(17)
        x = np.random.default_rng(3).random((500, 3), dtype=np.float32)
        tetra = lut.sample(x, "tetrahedral")
        tri = lut.sample(x, "trilinear")
        np.testing.assert_allclose(tetra, x, atol=1e-6)
        np.testing.assert_allclose(tri, x, atol=1e-6)

    def test_domain_min_max_respected(self):
        lut = LUT3D.identity(5)
        lut.domain_min = np.array([0.1, 0.1, 0.1], dtype=np.float32)
        lut.domain_max = np.array([0.6, 0.6, 0.6], dtype=np.float32)
        v = np.array([[0.1, 0.35, 0.6]], dtype=np.float32)
        got = lut.sample(v, "tetrahedral")[0]
        np.testing.assert_allclose(got, [0.0, 0.5, 1.0], atol=1e-6)

    def test_axis_permutation_detected(self):
        rng = np.random.default_rng(11)
        lut = LUT3D(data=rng.random((5, 5, 5, 3), dtype=np.float32))
        x = np.array([[0.9, 0.1, 0.1]], dtype=np.float32)
        a = lut.sample(x)[0]
        b = lut.permuted().sample(x)[0]
        self.assertGreater(float(np.max(np.abs(a - b))), 0.05)

    def test_curve_lut_1d(self):
        g = np.linspace(0, 1, 65, dtype=np.float32)
        c = CurveLUT(data=np.stack([g ** 2, g, g], axis=-1))
        v = np.array([[0.5, 0.5, 0.5]], dtype=np.float32)
        np.testing.assert_allclose(c.sample(v)[0], [0.25, 0.5, 0.5], atol=1e-6)

    def test_parse_errors(self):
        with self.assertRaises(ValueError):
            parse_cube("LUT_3D_SIZE 2\n0 0 0\n")
        with self.assertRaises(ValueError):
            parse_cube("0 0 0\n")


class TestOccupancy(unittest.TestCase):
    def test_gap_detection(self):
        inp = np.arange(256)
        out = (np.arange(256) // 4) * 4
        occ = code_occupancy(inp, out, bits=8)
        self.assertEqual(occ["unique_outputs"], 64)
        self.assertGreaterEqual(occ["max_empty_run"], 3)


if __name__ == "__main__":
    unittest.main()
