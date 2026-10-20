# -*- coding: utf-8 -*-
"""CIEDE2000 与主管线（含 GLSL 镜像等价性）测试。"""

from __future__ import annotations

import unittest

import numpy as np

from magicrri import color, curve, fixtures, glsl_mirror, pipeline
from magicrri.metrics import ciede2000, SHARMA_TEST_PAIRS


class TestCIEDE2000(unittest.TestCase):
    def test_sharma_reference_pairs(self):
        for L1, a1, b1, L2, a2, b2, expected in SHARMA_TEST_PAIRS:
            got = float(ciede2000([L1, a1, b1], [L2, a2, b2]))
            self.assertAlmostEqual(got, expected, delta=1e-4,
                                   msg=f"({L1},{a1},{b1}) vs ({L2},{a2},{b2})")

    def test_identity_and_symmetry(self):
        lab = np.array([55.0, -12.0, 33.0])
        self.assertAlmostEqual(float(ciede2000(lab, lab)), 0.0, delta=1e-12)
        other = np.array([50.0, 5.0, -20.0])
        self.assertAlmostEqual(float(ciede2000(lab, other)),
                               float(ciede2000(other, lab)), delta=1e-12)


def _make_pipeline(**kw):
    restore = curve.make_restore_lut(curve.MAGICLOG_V2, size=kw.pop("size", 33))
    look = curve.make_arri_lookup_lut(size=kw.pop("look_size", 33))
    return pipeline.Pipeline(pipeline.PipelineConfig(restore_lut=restore, look_lut=look, **kw))


class TestPipeline(unittest.TestCase):
    def setUp(self):
        self.p = _make_pipeline()
        self.cc = fixtures.colorchecker_reference_linear()
        self.log = curve.encode_with_iso(self.cc, curve.MAGICLOG_V2, 100)

    def test_self_consistency_delta_e(self):
        out = self.p(self.log)
        de = ciede2000(fixtures.lab_of_709_code(out), fixtures.reference_lab())
        self.assertLess(float(np.mean(de)), 1.5, "闭环管线与参考的平均 ΔE 应很小")

    def test_neutral_ramp_monotonic(self):
        ramp = np.linspace(0.0, 1.0, 256, dtype=np.float32)
        img = np.stack([ramp] * 3, axis=-1)
        out = self.p(img)[:, 0]
        self.assertTrue(np.all(np.diff(out) >= -1e-6), "灰阶必须单调不减")

    def test_skip_oetf_inverse_changes_result(self):
        out_ok = self.p(self.log)
        p_bad = _make_pipeline(decode_mode="none")
        out_bad = p_bad(self.log)
        self.assertGreater(float(np.max(np.abs(out_ok - out_bad))), 0.03)

    def test_skip_gamut_raises_saturation(self):
        out_ok = self.p(self.log)
        p_bad = _make_pipeline(gamut_convert=False)
        out_bad = p_bad(self.log)
        def chroma(o):
            lab = fixtures.lab_of_709_code(np.clip(o, 0, 1))
            return np.hypot(lab[:, 1], lab[:, 2])
        self.assertGreater(float(chroma(out_bad).mean()), float(chroma(out_ok).mean()))

    def test_logc3_stage_stays_in_lut_domain(self):
        st = self.p.apply(self.log)
        self.assertLess(float(np.max(st["logc3"])), 1.0)
        self.assertLess(float(np.max(st["logc3"])), 0.575)

    def test_glsl_mirror_equivalence(self):
        """着色器逻辑与 numpy 管线必须逐像素等价（float32 误差内）。"""
        cfg = self.p.cfg
        mirror_cfg = {
            "restore_lut": cfg.restore_lut,
            "look_lut": cfg.look_lut,
            "decode_mode": "oetf",
            "gamut_convert": True,
            "interp": "tetrahedral",
            "clip_output": True,
            "m709_to_awg3": color.matrix_709_to_awg3(),
        }
        rng = np.random.default_rng(2024)
        pts = rng.random((40, 3), dtype=np.float32) * 0.6 + 0.05
        got = glsl_mirror.pipeline_image(mirror_cfg, pts)
        want = self.p(pts)
        err = float(np.max(np.abs(got - want)))
        self.assertLess(err, 5e-6, f"GLSL 镜像与管线最大偏差 {err}")


if __name__ == "__main__":
    unittest.main()
