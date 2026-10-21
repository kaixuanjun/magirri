# -*- coding: utf-8 -*-
"""核心数学单元测试：python -m unittest discover -s tests -v"""

from __future__ import annotations

import unittest

import numpy as np

from vex import transfers as T


class TestLogC3(unittest.TestCase):
    def test_piecewise_continuity(self):
        self.assertLess(T.LOGC3_EI800.continuity_error(), 1e-5)

    def test_mid_gray_code(self):
        # ARRI LogC3 EI800：18% 灰应落在 0.391 附近（业界常用的 0.391/0.39）
        code = float(T.logc3_encode(np.array([0.18]))[0])
        self.assertAlmostEqual(code, 0.391, delta=0.001)

    def test_encode_decode_roundtrip(self):
        x = np.linspace(0.0, 1.0, 4097, dtype=np.float32)
        back = T.logc3_decode(T.logc3_encode(x))
        self.assertLess(float(np.max(np.abs(back - x))), 2e-6)

    def test_black_and_white_codes(self):
        rng = T.logc3_code_range()
        self.assertAlmostEqual(rng["code_at_linear_0"], 0.092809, delta=1e-5)
        self.assertAlmostEqual(rng["code_at_linear_1"], 0.570632, delta=1e-5)
        # 原生 LogC3 码值 1.0 对应线性 ≈55，故本管线在顶部丢掉的档数≈5.78
        self.assertAlmostEqual(rng["pipeline_lost_stops_at_top"], 5.783, delta=0.01)

    def test_negative_input_allowed(self):
        self.assertLess(float(T.logc3_encode(np.array([-0.01]))[0]), 0.092809)


class TestRec709(unittest.TestCase):
    def test_knee_continuity(self):
        """BT.709 的 (1.099, 0.099, 0.45) 常数在 0.018 处并不严格连续。

        4.5*0.018 = 0.0810，而 1.099*0.018^0.45-0.099 = 0.08125，
        跳变 2.5e-4（10-bit 下约 0.26 个码值）。这是标准常数的固有近似，
        不是实现错误；此处把量级钉住，防止有人"顺手修正"而偏离标准。
        """
        lo = float(T.rec709_oetf(np.array([0.018 - 1e-7]))[0])
        hi = float(T.rec709_oetf(np.array([0.018 + 1e-7]))[0])
        self.assertLess(abs(lo - hi), 3e-4)
        self.assertGreater(abs(lo - hi), 5e-5)

    def test_roundtrip(self):
        x = np.linspace(0.0, 1.0, 4097, dtype=np.float32)
        back = T.rec709_oetf_inverse(T.rec709_oetf(x))
        self.assertLess(float(np.max(np.abs(back - x))), 2e-6)

    def test_known_points(self):
        self.assertAlmostEqual(float(T.rec709_oetf(np.array([0.018]))[0]), 0.08125, delta=1e-5)
        self.assertAlmostEqual(float(T.rec709_oetf(np.array([0.18]))[0]), 0.409, delta=0.002)

    def test_eotf_differs_from_oetf_inverse(self):
        v = np.array([0.18, 0.4, 0.7], dtype=np.float32)
        diff = np.abs(T.rec709_eotf(v) - T.rec709_oetf_inverse(v))
        self.assertTrue(np.all(diff > 1e-3), "gamma2.4 与 OETF 逆必须在中间调有可见差异")


if __name__ == "__main__":
    unittest.main()
