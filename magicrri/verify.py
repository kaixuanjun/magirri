# -*- coding: utf-8 -*-
"""验证与报告生成：``python -m magicrri verify --out report``。

产出的文件
----------
``report/report.md``      主报告（结论 + 全部实验表格 + 图）
``report/metrics.json``   机器可读的全部指标
``report/figures/*.png``  图
``report/images/*.png``   端到端对照图
``report/luts/*.cube``    合成 LUT（可被真实 LUT 直接替换）

实验编号与指导书 §4 的对应关系写在报告正文里。
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List

import numpy as np

from . import color, curve, cube, fixtures, metrics, pipeline, transfers

__all__ = ["run", "main"]

BITS = 10


# ==========================================================================
# 基础设施
# ==========================================================================

def _ensure(root: str) -> Dict[str, str]:
    paths = {
        "root": root,
        "figures": os.path.join(root, "figures"),
        "images": os.path.join(root, "images"),
        "luts": os.path.join(root, "luts"),
    }
    for p in paths.values():
        os.makedirs(p, exist_ok=True)
    return paths


def _save_png(path: str, rgb_u8: np.ndarray) -> None:
    from PIL import Image

    Image.fromarray(np.asarray(rgb_u8, dtype=np.uint8)).save(path)


def _plt():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update({
        "figure.dpi": 110,
        "font.size": 9,
        "axes.grid": True,
        "grid.alpha": 0.25,
        "axes.titlesize": 10,
    })
    return plt


def _lab(codes) -> np.ndarray:
    return fixtures.lab_of_709_code(codes)


def _de(a, b) -> np.ndarray:
    return metrics.ciede2000(_lab(a), _lab(b))


# ==========================================================================
# 上下文
# ==========================================================================

def build_context(lut_size: int = 33) -> Dict[str, Any]:
    cc_lin = fixtures.colorchecker_reference_linear()
    log_in = curve.encode_with_iso(cc_lin, curve.MAGICLOG_V2, 100.0)
    ctx: Dict[str, Any] = {
        "cc_lin": cc_lin,
        "log_in": log_in,
        "restore33": curve.make_restore_lut(curve.MAGICLOG_V2, size=lut_size),
        "look33": curve.make_arri_lookup_lut(size=lut_size, look="neutral"),
        "restore65": curve.make_restore_lut(curve.MAGICLOG_V2, size=65),
        "look65": curve.make_arri_lookup_lut(size=65, look="neutral"),
        "restore_v1": curve.make_restore_lut(curve.MAGICLOG_V1, size=lut_size),
        "restore_exact": curve.make_restore_lut(curve.MAGICLOG_V2, size=4096, as_3d=False),
        "look_arri_like": curve.make_arri_lookup_lut(size=lut_size, look="arri_like"),
    }
    return ctx


def make_pipe(ctx: Dict[str, Any], **kw) -> pipeline.Pipeline:
    restore = kw.pop("restore_lut", ctx["restore33"])
    look = kw.pop("look_lut", ctx["look33"])
    return pipeline.Pipeline(pipeline.PipelineConfig(restore_lut=restore, look_lut=look, **kw))


# ==========================================================================
# E0 数学自检
# ==========================================================================

def exp0_math() -> Dict[str, Any]:
    p = transfers.LOGC3_EI800
    rng = transfers.logc3_code_range()
    lin = np.linspace(0.0, 1.0, 8193, dtype=np.float64).astype(np.float32)
    rt_logc3 = float(np.max(np.abs(transfers.logc3_decode(transfers.logc3_encode(lin)) - lin)))
    rt_709 = float(np.max(np.abs(transfers.rec709_oetf_inverse(transfers.rec709_oetf(lin)) - lin)))
    return {
        "logc3_params": {
            "cut": p.cut, "a": p.a, "b": p.b, "c": p.c, "d": p.d, "e": p.e, "f": p.f,
        },
        "logc3_continuity_error": p.continuity_error(),
        "logc3_roundtrip_max_abs": rt_logc3,
        "rec709_roundtrip_max_abs": rt_709,
        "rec709_knee_jump": float(
            transfers.rec709_oetf(np.array([0.018 + 1e-7]))[0]
            - transfers.rec709_oetf(np.array([0.018 - 1e-7]))[0]
        ),
        "logc3_code_range": rng,
        "matrix": color.arri_matrix_crosscheck(),
        # Rec.709 三个基色在 AWG3 里的坐标（矩阵的列）。
        # 出现负值 = 该基色落在 AWG3 三角形之外，这一步必然产生负信号。
        "primaries_709_in_awg3": {
            "R": color.matrix_709_to_awg3()[:, 0].tolist(),
            "G": color.matrix_709_to_awg3()[:, 1].tolist(),
            "B": color.matrix_709_to_awg3()[:, 2].tolist(),
        },
        "magiclog_v2": curve.MAGICLOG_V2.describe(),
        "magiclog_v1": curve.MAGICLOG_V1.describe(),
    }


# ==========================================================================
# E1 端到端保真度
# ==========================================================================

def exp1_fidelity(ctx) -> Dict[str, Any]:
    pipe = make_pipe(ctx)
    st = pipe.apply(ctx["log_in"])
    out = st["output"]
    lab_ref = fixtures.reference_lab()
    lab_out = _lab(out)
    de = metrics.ciede2000(lab_out, lab_ref)
    d_ab = np.hypot(lab_out[:, 1] - lab_ref[:, 1], lab_out[:, 2] - lab_ref[:, 2])
    rows = []
    ref_codes = np.array([r[1:] for r in fixtures.COLORCHECKER_SRGB8], dtype=np.float32) / 255.0
    for i, name in enumerate(fixtures.colorchecker_names()):
        rows.append({
            "index": i,
            "name": name,
            "ref_code": ref_codes[i].tolist(),
            "out_code": out[i].tolist(),
            "dE2000": float(de[i]),
            "dL": float(lab_out[i, 0] - lab_ref[i, 0]),
            "dC_ab": float(d_ab[i]),
        })
    skin = de[fixtures.SKIN_PATCHES]
    neutral = de[fixtures.NEUTRAL_PATCHES]
    stats = metrics.delta_stats(de)
    return {
        "rows": rows,
        "stats": stats,
        "skin_mean": float(skin.mean()),
        "skin_max": float(skin.max()),
        "neutral_mean": float(neutral.mean()),
        "neutral_max": float(neutral.max()),
        "worst_patch": fixtures.colorchecker_names()[int(np.argmax(de))],
        "thresholds": {"mean_lt": 5.0, "skin_lt": 4.0},
        "pass": {
            "mean": bool(stats["mean"] < 5.0),
            "skin": bool(float(skin.mean()) < 4.0),
        },
        "stages": pipe.stage_report(ctx["log_in"]),
    }


# ==========================================================================
# E2 消融 / 灵敏度
# ==========================================================================

def _ablation_specs(ctx) -> List[Dict[str, Any]]:
    iso1600 = curve.encode_with_iso(ctx["cc_lin"], curve.MAGICLOG_V2, 1600.0)
    return [
        dict(key="baseline", label="基线（正确配置）", note="全部步骤到位", kwargs={}),
        dict(key="no_oetf_inverse", label="遗漏 Rec.709 OETF 逆",
             note="把显示码值直接当线性光送进 LogC3 编码", kwargs={"decode_mode": "none"}),
        dict(key="gamma24_instead", label="用 gamma 2.4 代替 OETF 逆",
             note="常见的第二类错误：把 EOTF 当成 OETF 的逆", kwargs={"decode_mode": "gamma24"}),
        dict(key="no_gamut", label="跳过 Rec.709 → AWG3 基色变换",
             note="直接以 709 数值冒充 AWG3", kwargs={"gamut_convert": False}),
        dict(key="trilinear33", label="三线性插值（33³）",
             note="替代四面体插值", kwargs={"interp": "trilinear"}),
        dict(key="lut65", label="65³ LUT + 四面体",
             note="验证 LUT 尺寸是否够用",
             kwargs={"restore_lut": ctx["restore65"], "look_lut": ctx["look65"]}),
        dict(key="exact_restore", label="解析式还原（不查表）",
             note="把还原 LUT 换成 4096 点曲线，隔离 LUT 离散化误差",
             kwargs={"restore_lut": ctx["restore_exact"]}),
        dict(key="input_8bit", label="输入 8-bit 量化",
             note="10-bit 底线被破坏的情形", kwargs={"input_bits": 8}),
        dict(key="restore_axis_swapped", label="还原 LUT 的 R/B 轴读反",
             note="解析顺序写错时的真实后果", kwargs={"perturb_restore_order": True}),
        dict(key="look_axis_swapped", label="ARRI LUT 的 R/B 轴读反",
             note="同样错误在 3D Look LUT 上被放大", kwargs={"perturb_look_order": True}),
        dict(key="wrong_curve_version", label="用 v1 还原 LUT 处理 v2 素材",
             note="两代曲线参数不同（机型分两包发布）",
             kwargs={"restore_lut": ctx["restore_v1"]}),
        dict(key="iso1600_footage", label="ISO1600 素材 + ISO100 的 LUT",
             note="单条曲线覆盖所有 ISO 的残差", kwargs={}, input_override=iso1600),
        dict(key="matrix_reference", label="换用「流传版」AWG3 矩阵",
             note="矩阵来源未核实带来的饱和度漂移",
             kwargs={"matrix_709_to_awg3": color.REFERENCE_709_TO_AWG3_UNVERIFIED}),
    ]


def exp2_ablations(ctx) -> Dict[str, Any]:
    base_out = make_pipe(ctx)(ctx["log_in"])
    rows = []
    for spec in _ablation_specs(ctx):
        pipe = make_pipe(ctx, **dict(spec["kwargs"]))
        x = spec.get("input_override", ctx["log_in"])
        out = pipe(x)
        de_vs_base = _de(out, base_out)
        de_vs_ref = metrics.ciede2000(_lab(out), fixtures.reference_lab())
        lab = _lab(out)
        chroma = np.hypot(lab[:, 1], lab[:, 2]).mean()
        rows.append({
            "key": spec["key"],
            "label": spec["label"],
            "note": spec["note"],
            "dE_vs_baseline_mean": float(de_vs_base.mean()),
            "dE_vs_baseline_max": float(de_vs_base.max()),
            "dE_vs_reference_mean": float(de_vs_ref.mean()),
            "max_code_dev": float(np.max(np.abs(out - base_out))),
            "mean_chroma": float(chroma),
            "out_min": float(out.min()),
            "out_max": float(out.max()),
        })
    base_lab = _lab(base_out)
    base_chroma = float(np.hypot(base_lab[:, 1], base_lab[:, 2]).mean())
    for r in rows:
        r["chroma_ratio"] = r["mean_chroma"] / base_chroma
    return {"rows": rows, "baseline_chroma": base_chroma}


# ==========================================================================
# E3 量化与色阶断裂
# ==========================================================================

def _ramp_stats(ctx, input_bits: int) -> Dict[str, Any]:
    """在给定输入位深下，测灰阶楔经管线后的输出码值占用（输出恒按 10-bit 计）。

    只统计真正在变化的区间：输入码值低于 LUT 定义域下限时输出被钳在底部平台，
    高于上限时钳在顶部平台。这两段是 LUT 的定义域边界，不是量化误差，
    必须排除，否则统计会失真。
    """
    n = 1 << input_bits
    v = np.arange(n, dtype=np.float32) / np.float32(n - 1)
    out = make_pipe(ctx, input_bits=input_bits)(np.stack([v] * 3, axis=-1))[:, 0]
    codes = np.rint(out * 1023.0).astype(np.int64)
    bot = int(codes.min())
    top = int(codes.max())
    active = (codes > bot) & (codes < top)
    occ = metrics.code_occupancy(np.arange(n)[active], codes[active], bits=10)
    uniq = np.unique(codes[active])
    if uniq.size >= 2:
        rgb = np.stack([uniq.astype(np.float32) / 1023.0] * 3, axis=-1)
        max_step_de = float(np.max(_de(rgb[1:], rgb[:-1])))
    else:
        max_step_de = 0.0
    idx = np.nonzero(active)[0]
    occ.update({
        "input_bits": input_bits,
        "active_input_span": [int(idx[0]), int(idx[-1])] if idx.size else [0, 0],
        "active_input_count": int(idx.size),
        "bottom_plateau": bot,
        "top_plateau": top,
        "plateau_input_codes": int(n - idx.size) if idx.size else n,
        "max_adjacent_dE": max_step_de,
        "output_span": [int(uniq[0]), int(uniq[-1])] if uniq.size else [0, 0],
        "stretch": float((int(uniq[-1]) - int(uniq[0])) / max(1, int(idx.size))) if idx.size else 0.0,
    })
    return {"occ": occ, "codes": codes, "out": out, "input_n": n}


def exp3_banding(ctx) -> Dict[str, Any]:
    r10 = _ramp_stats(ctx, 10)
    r8 = _ramp_stats(ctx, 8)
    return {
        "input10": r10["occ"],
        "input8": r8["occ"],
        "ramp10": {"codes": r10["codes"].tolist(), "output": r10["out"].tolist()},
        "ramp8": {"codes": r8["codes"].tolist(), "output": r8["out"].tolist()},
    }


# ==========================================================================
# E4 动态范围预算
# ==========================================================================

def exp4_dynamic_range(ctx) -> Dict[str, Any]:
    pipe = make_pipe(ctx)
    stops = np.round(np.arange(-8.0, 8.01, 0.5), 3)
    lin = 0.18 * (2.0 ** stops)
    lin3 = np.stack([lin] * 3, axis=-1).astype(np.float32)
    log = curve.encode_with_iso(lin3, curve.MAGICLOG_V2, 100.0)
    st = pipe.apply(log)
    out_code = st["output"][:, 0]
    log_code = st["input"][:, 0]
    logc3 = st["logc3"][:, 0]
    rows = []
    peak = float(out_code.max())
    # "饱和"以输出进入平台为准（比峰值低不到 0.5 个 10-bit 步长）。
    # 不能直接与 1.0 比较：33³ LUT 在 LogC3 顶端插值后常常到不了满量程（见 E5）。
    plateau = out_code >= peak - 0.5 / 1023.0
    for i, s in enumerate(stops):
        rows.append({
            "stops_vs_mid": float(s),
            "scene_linear": float(lin[i]),
            "magiclog_code": float(log_code[i]),
            "logc3_code": float(logc3[i]),
            "out_code": float(out_code[i]),
            "out_10bit": int(round(float(out_code[i]) * 1023)),
            "clipped": bool(plateau[i]),
        })
    # 可再现的最高场景线性值
    ok = ~plateau
    max_rep = float(lin[ok][-1]) if ok.any() else 0.0
    rng = transfers.logc3_code_range()
    return {
        "rows": rows,
        "max_reproducible_linear": max_rep,
        "max_reproducible_stops_vs_mid": float(np.log2(max_rep / 0.18)),
        "native_logc3_max_linear": rng["linear_at_code_1"],
        "native_logc3_stops_vs_mid": rng["stops_from_mid_to_code_1"],
        "lost_stops": float(rng["stops_from_mid_to_code_1"] - np.log2(max_rep / 0.18)),
        "logc3_domain_used": [float(logc3.min()), float(logc3.max())],
        "peak_out_code": peak,
    }


# ==========================================================================
# E5 LUT 采样精度
# ==========================================================================

def exp5_lut_precision(ctx) -> Dict[str, Any]:
    ref_pipe = make_pipe(ctx, restore_lut=ctx["restore_exact"])
    ref_out = ref_pipe(ctx["log_in"])
    rows = []
    for name, lut, interp in (
        ("33³ 四面体", ctx["restore33"], "tetrahedral"),
        ("33³ 三线性", ctx["restore33"], "trilinear"),
        ("33³ 最近邻", ctx["restore33"], "nearest"),
        ("65³ 四面体", ctx["restore65"], "tetrahedral"),
    ):
        out = make_pipe(ctx, restore_lut=lut, interp=interp)(ctx["log_in"])
        de = _de(out, ref_out)
        rows.append({
            "label": name,
            "dE_mean": float(de.mean()),
            "dE_max": float(de.max()),
            "max_code_dev": float(np.max(np.abs(out - ref_out))),
        })
    # 曲线解析值与 33³ 查表值的最大码值差（纯采样误差）
    grid = np.linspace(0.0, 1.0, 4096, dtype=np.float32)
    gray = np.stack([grid] * 3, axis=-1)
    exact = ctx["restore_exact"].sample(gray)
    lut33 = ctx["restore33"].sample(gray)
    return {
        "rows": rows,
        "curve_vs_lut33_max_code_err": float(np.max(np.abs(exact - lut33))),
        "curve_vs_lut33_mean_code_err": float(np.mean(np.abs(exact - lut33))),
    }


# ==========================================================================
# 图
# ==========================================================================

def fig_transfers(path: str) -> None:
    plt = _plt()
    fig, axes = plt.subplots(1, 2, figsize=(9.2, 3.4))
    lin = np.logspace(-4, 0.3, 400)
    axes[0].semilogx(lin, transfers.logc3_encode(lin.astype(np.float32)), lw=1.6,
                     label="ARRI LogC3 EI800")
    axes[0].semilogx(lin, curve.MAGICLOG_V2.encode(lin.astype(np.float32)), lw=1.4,
                     ls="--", label="synthetic Magic-Log v2")
    axes[0].axhline(0.391, color="0.6", lw=0.8, ls=":")
    axes[0].annotate("18% grey = 0.3910", (1e-4, 0.395), fontsize=8, color="0.35")
    axes[0].axhline(0.5706, color="C3", lw=0.8, ls=":")
    axes[0].annotate("linear 1.0 = 0.5706", (1e-4, 0.575), fontsize=8, color="C3")
    axes[0].set_xlabel("scene-linear")
    axes[0].set_ylabel("log code")
    axes[0].set_title("Log encoding curves")
    axes[0].legend(loc="lower right", fontsize=8)

    v = np.linspace(0.0, 1.0, 400)
    axes[1].plot(v, transfers.rec709_oetf_inverse(v), lw=1.6, label="Rec.709 OETF$^{-1}$ (correct)")
    axes[1].plot(v, transfers.rec709_eotf(v), lw=1.4, ls="--", label="gamma 2.4 EOTF (wrong)")
    axes[1].plot(v, v, lw=0.8, color="0.7", ls=":", label="no decode (wrong)")
    axes[1].set_xlabel("Rec.709 display code")
    axes[1].set_ylabel("linear")
    axes[1].set_title("Decoding a Rec.709 restoration LUT")
    axes[1].legend(loc="upper left", fontsize=8)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_tone_curve(ctx, path: str) -> None:
    plt = _plt()
    pipe = make_pipe(ctx)
    ramp = np.linspace(0.0, 1.0, 1024, dtype=np.float32)
    out = pipe(np.stack([ramp] * 3, axis=-1))[:, 0]
    fig, ax = plt.subplots(figsize=(5.6, 4.4))
    ax.plot(ramp * 1023, out * 1023, lw=1.6, label="pipeline (neutral axis)")
    ax.plot([0, 1023], [0, 1023], color="0.7", lw=0.8, ls=":", label="identity")
    mid = int(0.391 * 1023)
    ax.axvline(mid, color="C2", lw=0.8, ls="--")
    ax.annotate("18% grey\nin = 0.3910", (mid - 95, 120), fontsize=8, color="C2")
    peak = float(out.max())
    plateau = out >= peak - 0.5 / 1023.0
    clip = int(np.argmax(plateau)) if plateau.any() else len(out) - 1
    ax.axvline(clip, color="C3", lw=0.8, ls="--")
    ax.annotate(f"clips at input code {clip}\n(max out = {int(round(peak * 1023))}/1023)",
                (clip + 20, 300), fontsize=8, color="C3")
    ax.set_xlabel("input Magic-Log code (10-bit)")
    ax.set_ylabel("output Rec.709 code (10-bit)")
    ax.set_title("Per-channel tone response of the whole pipeline")
    ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_delta_e(e1, e2, path: str) -> None:
    plt = _plt()
    names = [r["name"].split(" ")[0] for r in e1["rows"]]
    base = [r["dE2000"] for r in e1["rows"]]
    fig, axes = plt.subplots(1, 2, figsize=(11.0, 3.8),
                             gridspec_kw={"width_ratios": [1.35, 1.0]})
    ax = axes[0]
    x = np.arange(len(names))
    ax.bar(x, base, 0.7, color="C0")
    ax.axhline(5.0, color="C3", lw=0.9, ls="--", label="acceptance threshold (5.0)")
    ax.set_xticks(x)
    ax.set_xticklabels(names, rotation=60, ha="right", fontsize=7)
    ax.set_ylabel("ΔE2000")
    ax.set_title(f"Baseline ΔE per patch (mean {np.mean(base):.2f}, max {np.max(base):.2f})",
                 fontsize=9)
    ax.legend(fontsize=7)

    ax2 = axes[1]
    rows = [r for r in e2["rows"] if r["key"] != "baseline"]
    rows = sorted(rows, key=lambda r: r["dE_vs_baseline_mean"])
    y = np.arange(len(rows))
    ax2.barh(y, [r["dE_vs_baseline_mean"] for r in rows], 0.7, color="C1")
    ax2.set_yticks(y)
    ax2.set_yticklabels([r["key"] for r in rows], fontsize=7)
    for yi, r in zip(y, rows):
        ax2.text(r["dE_vs_baseline_mean"] + 0.3, yi,
                 f"{r['dE_vs_baseline_mean']:.2f}", va="center", fontsize=6.5)
    ax2.set_xlabel("ΔE2000 vs baseline (mean over 24 patches)")
    ax2.set_title("Cost of each broken step", fontsize=9)
    ax2.set_xlim(0, max(r["dE_vs_baseline_mean"] for r in rows) * 1.18)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_ab_scatter(ctx, e1, path: str) -> None:
    plt = _plt()
    ref = fixtures.reference_lab()
    base = _lab(make_pipe(ctx)(ctx["log_in"]))
    nog = _lab(make_pipe(ctx, gamut_convert=False)(ctx["log_in"]))
    fig, ax = plt.subplots(figsize=(4.8, 4.4))
    ax.axhline(0, color="0.8", lw=0.8)
    ax.axvline(0, color="0.8", lw=0.8)
    ax.scatter(ref[:, 1], ref[:, 2], s=34, facecolors="none", edgecolors="0.2",
               label="reference (ColorChecker)")
    ax.scatter(base[:, 1], base[:, 2], s=18, marker="x", color="C0", label="pipeline baseline")
    ax.scatter(nog[:, 1], nog[:, 2], s=18, marker="+", color="C3", label="no 709→AWG3 matrix")
    ax.set_xlabel("a*")
    ax.set_ylabel("b*")
    ax.set_title("Chroma plane (D65 Lab)")
    ax.legend(fontsize=7)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_banding(e3, path: str) -> None:
    plt = _plt()
    fig, axes = plt.subplots(1, 2, figsize=(9.4, 3.6))
    for ax, key, title in (
        (axes[0], "ramp10", "10-bit input"),
        (axes[1], "ramp8", "8-bit input"),
    ):
        used = np.asarray(e3[key]["codes"], dtype=np.int64)
        inp = np.arange(used.size)
        ax.plot(inp, used, lw=1.1)
        ax.plot([0, inp.size - 1], [0, inp.size - 1], color="0.8", lw=0.7, ls=":")
        occ = e3["input10"] if key == "ramp10" else e3["input8"]
        ax.set_title(f"{title}: {occ['unique_outputs']} distinct 10-bit output codes, "
                     f"max gap {occ['max_empty_run']}", fontsize=9)
        ax.set_xlabel("input code")
        ax.set_ylabel("output code (10-bit)")
        span = occ["active_input_span"]
        ax.annotate(f"active domain {span[0]}..{span[1]}", (inp.size * 0.05, used.max() * 0.45),
                    fontsize=7, color="C3")
    fig.suptitle("Neutral ramp through the pipeline (flat tails = LUT domain edges)", fontsize=10)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


# ==========================================================================
# 报告
# ==========================================================================

def _md_table(headers: List[str], rows: List[List[Any]]) -> str:
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def _fmt(x, nd=2):
    return f"{x:.{nd}f}"


def write_report(path: str, e0, e1, e2, e3, e4, e5, meta: Dict[str, Any]) -> None:
    L: List[str] = []
    A = L.append

    A("# Magic-Log → LogC3 实时映射管线：桌面参考实现与量化验证报告\n")
    A(f"> 生成命令：`python -m magicrri verify --out report`  ")
    A(f"> 版本：magicrri {meta['version']}　numpy {meta['numpy']}  ")
    A("> **所有 LUT 均为合成替身**（没有荣耀 / ARRI 的原始 `.cube`）。"
      "本报告验证的是**实现的正确性与数值边界**，不是对 ARRI 色彩的保真度。\n")

    # ---------------- 0. 结论 ----------------
    A("## 0. 结论摘要\n")
    ok_mean = e1["pass"]["mean"]
    ok_skin = e1["pass"]["skin"]
    A(f"1. **管线数值闭环成立。** 24 色块端到端 ΔE2000 均值 **{_fmt(e1['stats']['mean'])}**、"
      f"中位 {_fmt(e1['stats']['median'])}、P95 {_fmt(e1['stats']['p95'])}、最大 {_fmt(e1['stats']['max'])}"
      f"（最差色块：{e1['worst_patch']}）；肤色均值 **{_fmt(e1['skin_mean'])}**，灰阶均值 "
      f"{_fmt(e1['neutral_mean'])}。指导书 §4 的门槛是「中间调均值 < 5、肤色 < 4」，"
      f"**{'两项均通过' if (ok_mean and ok_skin) else '未全部通过'}**。"
      f"需要注意：这是**自洽闭环**下的数字（合成 LUT 由同一套数学生成），它证明实现没错，"
      f"不证明结果像 ARRI。\n")
    A(f"2. **三个最容易翻车的点，代价已被量化。** 遗漏 Rec.709 OETF 逆 → 平均 ΔE "
      f"**{_fmt(_row(e2, 'no_oetf_inverse')['dE_vs_baseline_mean'])}**；用 gamma 2.4 顶替 → "
      f"**{_fmt(_row(e2, 'gamma24_instead')['dE_vs_baseline_mean'])}**；跳过 709→AWG3 → "
      f"**{_fmt(_row(e2, 'no_gamut')['dE_vs_baseline_mean'])}**，且饱和度整体偏 "
      f"**{_fmt((_row(e2, 'no_gamut')['chroma_ratio'] - 1) * 100, 1)}%**。\n")
    A(f"3. **宽容度损失是硬边界，不是调参能补的。** 管线最高只能到 LogC3 码值 "
      f"{_fmt(e0['logc3_code_range']['pipeline_max_code'], 4)}（线性 1.0），而原生 LogC3 在码值 1.0 "
      f"处对应线性 {_fmt(e0['logc3_code_range']['linear_at_code_1'], 1)}（{_fmt(e0['logc3_code_range']['stops_from_mid_to_code_1'], 2)} 档）。"
      f"**顶部实际损失 {_fmt(e4['lost_stops'], 2)} 档**；18% 灰以上只剩 "
      f"{_fmt(e4['max_reproducible_stops_vs_mid'], 2)} 档可用（实测饱和点 +"
      f"{_fmt(e4['max_reproducible_stops_vs_mid'], 1)} 档，理论值 +2.47 档）。\n")
    A(f"4. **10-bit 是底线这条判断成立。** 有效输入域内，10-bit 输入得到 "
      f"{e3['input10']['unique_outputs']} 个不同的 10-bit 输出码值、平均步长 "
      f"{_fmt(e3['input10']['mean_step'], 2)} 码、最大空档 {e3['input10']['max_empty_run']} 码；"
      f"换成 8-bit 输入后输出码值掉到 {e3['input8']['unique_outputs']} 个、平均步长 "
      f"{_fmt(e3['input8']['mean_step'], 2)} 码，相邻输出码值之间最大 ΔE 达 "
      f"**{_fmt(e3['input8']['max_adjacent_dE'], 2)}**（10-bit 为 "
      f"{_fmt(e3['input10']['max_adjacent_dE'], 2)}）——这就是渐变区会出现台阶的直接量度。\n")
    A(f"5. **LUT 顺序（R 变化最快）写反 = R/B 通道互换。** 还原 LUT 读反后 ΔE "
      f"**{_fmt(_row(e2, 'restore_axis_swapped')['dE_vs_baseline_mean'])}**，Look LUT 读反后 ΔE "
      f"**{_fmt(_row(e2, 'look_axis_swapped')['dE_vs_baseline_mean'])}**。"
      "两者都不是噪点或条纹，而是**系统性的品红/黄绿偏色**——它看起来「像某种色彩风格」，"
      "极容易被误判成「LUT 本身就这样」而放过。这就是必须用回归测试钉死顺序的原因。\n")
    A(f"6. **指导书 §3.2 的填充顺序写反了。** 规范与主流实现都是「红色变化最快」，"
      f"本条已在代码中按规范实现，并用官方最小示例做了回归测试"
      f"（`tests/test_color_cube.py::test_order_is_red_fastest`）。\n")
    A("7. **最关键的工程前提不成立：第三方 App 拿不到 Magic-Log。** Magic-Log 是荣耀相机 App "
      "内部的视频模式，Camera2 只暴露 RAW/YUV，不会给你那条厂商 Log 曲线。详见 §9。\n")

    # ---------------- 1. 交付物 ----------------
    A("\n## 1. 交付物与运行方式\n")
    A("```\n"
      "magicrri/\n"
      "  transfers.py     LogC3(EI800) 编解码、Rec.709 OETF 及其逆\n"
      "  color.py         基色→矩阵推导、709↔AWG3、Lab\n"
      "  cube.py          .cube 解析/写出、3D LUT 四面体与三线性采样\n"
      "  curve.py         合成类 Magic-Log 曲线（v1/v2 + ISO 漂移）、还原/ARRI LUT 替身\n"
      "  fixtures.py      ColorChecker 24 参考值、阶跃楔、色块图\n"
      "  pipeline.py      五阶段主管线（与 GLSL 逐行对应）\n"
      "  glsl_mirror.py   着色器逻辑的 CPU 标量镜像（等价性测试用）\n"
      "  metrics.py       CIEDE2000、色阶断裂、裁剪统计\n"
      "  verify.py        本报告的全部实验\n"
      "glsl/logc3_pipeline.frag   OpenGL ES 3.0 片段着色器\n"
      "tests/                     29 个单元测试\n"
      "```\n")
    A("```bash\n"
      "python -m magicrri verify --out report      # 复现本报告\n"
      "python -m magicrri info --lut my.cube       # 检查 LUT 头信息/域/尺寸\n"
      "python -m magicrri apply --restore r.cube --look l.cube --in in.png --out out.png\n"
      "python -m unittest discover -s tests        # 单元测试\n"
      "```\n")

    # ---------------- 2. 数学自检 ----------------
    A("\n## 2. E0 数学自检\n")
    g = e0["logc3_code_range"]
    A(_md_table(
        ["量", "值", "说明"],
        [
            ["LogC3 分段点（线性/码值）", f"{e0['logc3_params']['cut']:.6f} / {g['cut_code']:.6f}", "两段曲线在此拼接"],
            ["分段处跳变", f"{e0['logc3_continuity_error']:.2e}", "公开常数自带的舍入误差"],
            ["编码→解码往返最大误差", f"{e0['logc3_roundtrip_max_abs']:.2e}", "float32 下的实现误差"],
            ["709 OETF→逆往返最大误差", f"{e0['rec709_roundtrip_max_abs']:.2e}", "同上"],
            ["709 分段跳变", f"{e0['rec709_knee_jump']:.2e}", "标准常数 (1.099,0.099,0.45) 在 0.018 处本就不连续（约 0.26 个 10-bit 码值），不是 bug"],
            ["线性 0.18 → 码值", f"{g['code_at_linear_0_18']:.4f}", "ARRI 惯例的 0.391"],
            ["线性 1.0 → 码值", f"{g['code_at_linear_1']:.4f}", "管线输出的上限"],
            ["码值 1.0 → 线性", f"{g['linear_at_code_1']:.2f}", "原生 LogC3 的顶部余量"],
            ["18% 灰到码值 1.0", f"{g['stops_from_mid_to_code_1']:.2f} 档", "原生 LogC3 的标称上限"],
        ]))
    m = e0["matrix"]
    A("\n**色域矩阵。** 由基色色度坐标推导（不是抄数），行和 = "
      f"{', '.join(_fmt(v, 12) for v in m['awg3_to_709_row_sums'])}（白点守恒），"
      f"推导逆对往返误差 {m['derived_inverse_roundtrip_max_abs']:.2e}。\n")
    A(f"另有一组常被引用的 AWG3→709 矩阵，与推导结果最大相差 **{_fmt(m['awg3_to_709_max_abs_diff'], 4)}**。"
      f"反解该矩阵隐含的基色可得 B 基色色度 (0.0861, -0.102)——一个「虚基色」，说明它要么不是纯基色变换"
      f"（含厂商自有的渲染矩阵），要么转录有误。**本实现以可复算的推导矩阵为准，并在 E2 里量化了换用它"
      f"的代价**（ΔE {_fmt(_row(e2, 'matrix_reference')['dE_vs_baseline_mean'])}）。正式项目必须用 ARRI 官方文档或 "
      f"OCIO 配置核对这一步，不能靠记忆抄数。\n")
    p3 = e0["primaries_709_in_awg3"]
    A("\n**一个必须知道的结论：AWG3 并不包含 Rec.709。** 把 709 的三个基色送进 "
      "709→AWG3 矩阵，得到：\n")
    A(_md_table(
        ["709 基色", "在 AWG3 中的坐标 (R,G,B)", "是否越界"],
        [[k, "(" + ", ".join(f"{c:+.3f}" for c in v) + ")", "越界" if any(c < 0 for c in v) else "在界内"]
         for k, v in p3.items()]))
    A(f"\n纯 709 蓝在 AWG3 里是 ({', '.join(f'{c:+.3f}' for c in p3['B'])})——**绿通道深度为负**。"
      "也就是说 AWG3 的红、绿两角比 709 更宽，但**蓝角比 709 更靠内**（AWG3 蓝基色 "
      "y=0.154，709 蓝基色 y=0.060）。指导书 §2.4 的「AWG3 比 Rec.709 更宽」只对红/绿成立。\n"
      "后果是：`linear_awg3` 出现负值 → LogC3 编码后落在 LUT 定义域之外 → 被钳到 0 → "
      "**高饱和蓝/青被降饱和**。这就是 E1 里最差色块是 blue（ΔE "
      f"{_fmt(next(r['dE2000'] for r in e1['rows'] if r['name'].startswith('blue 蓝')), 2)}）"
      "的原因；它属于路线自带的色域裁切，不是实现错误。\n")
    A("如果这一点不可接受，有两条出路：(1) 换用蓝基色更靠外的色域与对应 Log（例如 ARRI 后续的 "
      "AWG4/LogC4，或直接以 ACES AP1 为工作空间），代价是整套曲线参数要换；"
      "(2) 在 LogC3 编码之后、LUT 之前插入一个「负值压平」的软钳位，把裁切改成渐变，"
      "避免硬拐点带来的色相跳变。\n")

    # ---------------- 3. E1 ----------------
    A("\n## 3. E1 端到端保真度（ColorChecker 24）\n")
    A("输入：ColorChecker 24 的参考线性反射率 → 合成 Magic-Log v2（ISO100）编码。"
      "参考：同一组色块的 709 显示码值（8-bit 参考值按 709 OETF 逆解码后转 Lab）。"
      "两侧使用同一条解码曲线，因此 ΔE 只反映管线误差。\n")
    rows = []
    for r in e1["rows"]:
        rows.append([
            r["index"] + 1,
            r["name"],
            " ".join(str(c) for c in np.round(np.array(r["ref_code"]) * 255).astype(int)),
            " ".join(str(c) for c in np.round(np.array(r["out_code"]) * 255).astype(int)),
            _fmt(r["dL"]),
            _fmt(r["dC_ab"]),
            _fmt(r["dE2000"]),
        ])
    A(_md_table(["#", "色块", "参考 8bit", "输出 8bit", "ΔL*", "ΔC*ab（色度距离）", "ΔE00"], rows))
    A("")
    A(_md_table(
        ["统计量", "全部 24 块", "肤色（2 块）", "灰阶（6 块）", "门槛"],
        [
            ["均值", _fmt(e1["stats"]["mean"]), _fmt(e1["skin_mean"]),
             _fmt(e1["neutral_mean"]), "< 5 / 肤色 < 4"],
            ["中位", _fmt(e1["stats"]["median"]), "—", "—", ""],
            ["P95", _fmt(e1["stats"]["p95"]), "—", "—", ""],
            ["最大", _fmt(e1["stats"]["max"]), _fmt(e1["skin_max"]),
             _fmt(e1["neutral_max"]), ""],
        ]))
    A(f"\n判定：中间调均值 < 5 → **{'通过' if ok_mean else '不通过'}**；"
      f"肤色均值 < 4 → **{'通过' if ok_skin else '不通过'}**。\n")
    A("各阶段数值区间（同一批色块）：\n")
    A(_md_table(["阶段", "min", "max", "mean"],
                [[s["stage"], _fmt(s["min"], 4), _fmt(s["max"], 4), _fmt(s["mean"], 4)]
                 for s in e1["stages"]]))
    A("\n两点说明：\n"
      "- `linear_awg3` / `logc3` 出现**负值**是正常的：把超饱和的 709 颜色送进 AWG3 "
      "会推到负的半轴，LogC3 本身在 0 以下也有定义（线性 0 对应码值 0.0928）。"
      "但 LUT 采样会把负码值钳到定义域下限，等于裁掉这部分负信号——"
      "这就是「高饱和红/蓝在 LUT 管线里被压平」的成因。\n"
      "- `restore` 阶段的 max 只到 0.94，说明合成还原 LUT 的输入域没有用满 [0,1]；"
      "真实 LUT 的有效域必须用 `probe` 标定。\n")
    A("![delta-e](figures/f3_delta_e.png)\n")
    A("![ab](figures/f4_ab_scatter.png)\n")

    # ---------------- 4. E2 ----------------
    A("\n## 4. E2 消融与灵敏度\n")
    A("每个变体与基线在同一输入上对比。「对基线 ΔE」= 该变体输出与基线输出的逐色块 ΔE；"
      "「对参考 ΔE」= 与 ColorChecker 参考的 ΔE。\n")
    rows = []
    for r in e2["rows"]:
        rows.append([
            r["label"],
            _fmt(r["dE_vs_baseline_mean"]),
            _fmt(r["dE_vs_baseline_max"]),
            _fmt(r["dE_vs_reference_mean"]),
            _fmt(r["max_code_dev"], 3),
            _fmt(r["chroma_ratio"], 3),
        ])
    A(_md_table(["变体", "对基线 ΔE 均", "对基线 ΔE 最大", "对参考 ΔE 均", "最大码值差", "彩度比"], rows))
    A("\n读法：")
    for r in e2["rows"][1:]:
        A(f"- **{r['label']}**（{r['note']}）：ΔE 均值 {_fmt(r['dE_vs_baseline_mean'])}，"
          f"彩度 ×{_fmt(r['chroma_ratio'], 3)}")
    A("")

    # ---------------- 5. E3 ----------------
    A("\n## 5. E3 量化与色阶断裂\n")
    A(_md_table(
        ["指标（输出统一按 10-bit 计）", "10-bit 输入", "8-bit 输入"],
        [
            ["未钳位的输入域（码值）", f"{e3['input10']['active_input_span'][0]} … "
                              f"{e3['input10']['active_input_span'][1]} "
                              f"（{e3['input10']['active_input_count']} 级）",
             f"{e3['input8']['active_input_span'][0]} … "
             f"{e3['input8']['active_input_span'][1]} "
             f"（{e3['input8']['active_input_count']} 级）"],
            ["底部/顶部平台（输出码值）", f"{e3['input10']['bottom_plateau']} / "
                                 f"{e3['input10']['top_plateau']}",
             f"{e3['input8']['bottom_plateau']} / {e3['input8']['top_plateau']}"],
            ["不同输出码值数", e3["input10"]["unique_outputs"], e3["input8"]["unique_outputs"]],
            ["平均输出步长（码值）", _fmt(e3["input10"]["mean_step"]), _fmt(e3["input8"]["mean_step"])],
            ["拉伸倍数（输出跨度/输入级数）", _fmt(e3["input10"]["stretch"]), _fmt(e3["input8"]["stretch"])],
            ["最大连续空档（码值）", e3["input10"]["max_empty_run"], e3["input8"]["max_empty_run"]],
            ["相邻输出码值最大 ΔE", _fmt(e3["input10"]["max_adjacent_dE"]),
             _fmt(e3["input8"]["max_adjacent_dE"])],
            ["输出码值范围", e3["input10"]["output_span"], e3["input8"]["output_span"]],
        ]))
    A(f"\n这里有个反直觉的地方：管线把一个较窄的输入域**拉伸**到几乎满输出域"
      f"（{_fmt(e3['input10']['stretch'])}×），所以 10-bit 输入几乎 1:1 落到输出"
      f"（平均 {_fmt(e3['input10']['mean_step'])} 码/级，最大空档 {e3['input10']['max_empty_run']} 码）；"
      f"但 8-bit 输入只有 {e3['input8']['active_input_count']} 级可拉，"
      f"输出端平均步长变成 {_fmt(e3['input8']['mean_step'])} 个 10-bit 码值、最大空档 "
      f"{e3['input8']['max_empty_run']} 码，相邻台阶 ΔE {_fmt(e3['input8']['max_adjacent_dE'], 2)}"
      " —— 这是渐变天空上看得见的台阶。"
      "**8-bit 采集在这条管线里没有经济性**，与指导书 §5 的判断一致。\n")
    A("![banding](figures/f5_banding.png)\n")

    # ---------------- 6. E4 ----------------
    A("\n## 6. E4 动态范围预算\n")
    A(_md_table(
        ["相对 18% 灰（档）", "场景线性", "Magic-Log 码", "伪 LogC3 码", "输出 709 码（10bit）", "被截断"],
        [[f"{r['stops_vs_mid']:+.1f}", _fmt(r["scene_linear"], 4), _fmt(r["magiclog_code"], 4),
          _fmt(r["logc3_code"], 4), r["out_10bit"], "是" if r["clipped"] else ""]
         for r in e4["rows"]]))
    A("")
    A(_md_table(
        ["量", "值"],
        [
            ["可再现的最高场景线性（相对 18% 灰）", f"{_fmt(e4['max_reproducible_linear'], 3)} "
                                                 f"（{_fmt(e4['max_reproducible_stops_vs_mid'], 2)} 档）"],
            ["原生 LogC3 上限（相对 18% 灰）", f"{_fmt(e4['native_logc3_max_linear'], 1)} "
                                            f"（{_fmt(e4['native_logc3_stops_vs_mid'], 2)} 档）"],
            ["顶部损失", f"{_fmt(e4['lost_stops'], 2)} 档"],
            ["管线实际占用的 LogC3 码值区间", f"{_fmt(e4['logc3_domain_used'][0], 3)} … "
                                        f"{_fmt(e4['logc3_domain_used'][1], 3)}（域 [0,1] 的 "
                                        f"{_fmt(e4['logc3_domain_used'][1] * 100, 1)}%）"],
        ]))
    A("\n这就是「经 Rec.709 中间步骤后高光余量被压缩过一次」的定量版本："
      "不是轻微退让，而是**整条高光肩部不存在**。任何超过 709 白点的信息在第一步就没了，"
      "后面再补曲线也补不回来。\n")
    A("![tone](figures/f2_tone_curve.png)\n")

    # ---------------- 7. E5 ----------------
    A("\n## 7. E5 LUT 采样精度\n")
    A(_md_table(["配置", "对解析式 ΔE 均", "ΔE 最大", "最大码值差"],
                [[r["label"], _fmt(r["dE_mean"], 3), _fmt(r["dE_max"], 3), _fmt(r["max_code_dev"], 4)]
                 for r in e5["rows"]]))
    A(f"\n33³ 查表相对解析曲线的码值误差：最大 {_fmt(e5['curve_vs_lut33_max_code_err'], 4)}、"
      f"平均 {_fmt(e5['curve_vs_lut33_mean_code_err'], 4)}。"
      "结论：33³ + 四面体已经够用（误差远低于 1 个 10-bit 码值）；"
      "三线性的误差在同一量级，但它是「轴对齐」插值，遇到大幅度色域映射的 Look LUT 会明显变差；"
      "最近邻完全不可用。\n")
    A(f"还有一条容易被忽略的：**33³ LUT 在 LogC3 顶端插值后到不了满量程**。"
      f"本例输出峰值只到 {_fmt(e4['peak_out_code'], 4)}，即 10-bit 码值 "
      f"{int(round(e4['peak_out_code'] * 1023))}/1023。原因是顶部格元跨过了陡峭的肩部，"
      "线性（含四面体）插值无法还原格元内部的拐点。这是网格尺寸的结构性问题："
      "若要求纯白精确到 100%，只能加大 LUT 尺寸，或在 LUT 之后补一级标量增益。"
      "如果发现「白总差一点」，先查这里，别去动 LUT。\n")

    # ---------------- 8. 与指导书的偏差 ----------------
    A("\n## 8. 与指导书的技术偏差（逐条）\n")
    A("| # | 指导书原文 | 本实现 / 实测 | 依据 |")
    A("|---|---|---|---|")
    A("| 1 | §3.2「按 R 变化最慢、B 变化最快 的顺序填充」 | **相反**：规范是红色变化最快 | "
      "Iridas `.cube` 最小示例：第 2 行是 `1 0 0`（R 满）。已写回归测试 |")
    A(f"| 2 | §2.3「LogC3 编码输出范围 [0,1]」 | 实际是 [0.0928, 0.5706]（线性 0→1）；"
      f"LUT 只用到域的下半部分 | 公式直接可得，见 E0 |")
    A("| 3 | §2.4「AWG3 比 Rec.709 更宽，同样的数值在 AWG3 中对应更低的饱和度感知」 | "
      "**结论对、因果反了**：跳过矩阵确实让饱和度**偏高**（实测 "
      f"×{_fmt(_row(e2, 'no_gamut')['chroma_ratio'], 3)}），但不是因为「AWG3 里数值更淡」，"
      "而是因为 709 基色坐标落在 AWG3 三角形**内部**，直接当 AWG3 数值读取再经 AWG3→709 "
      "往外扩了一次 | E2-no_gamut |")
    A("| 4 | §2.1「RAW 流不会经过 3A 的完整后处理，你需要在 App 层实现自己的曝光控制」 | "
      "3A 是控制环、不是后处理；RAW 绕过的是 ISP（去马赛克/降噪/tonemap）。"
      "3A 在 `CONTROL_MODE=AUTO` 下照常运行并把结果写进 metadata；只有关掉 AE 才需要自建曝光 | Camera2 语义 |")
    A("| 5 | §2.2「官方 LUT 输入是 Magic-Log 编码值，归一化到 [0,1]」 | 需实测确认："
      "还原 LUT 的域不一定覆盖满量程，也可能只覆盖实际使用区间。"
      "本仓库提供灰阶楔探针（`python -m magicrri probe --lut x.cube`）用于标定 | 合成夹具中已显式建模 |")
    A("| 6 | §2.3 「OETF 逆」阈值 0.081 | 正确，但注意标准常数在 0.018 处本身不连续（跳变 2.5e-4），"
      "不要试图「修正」它 | E0 |")
    A("| 7 | §4「目标：中间调 ΔE 均值 < 5、肤色 < 4」 | 采用，且补充了 P95/最大与彩度比 | E1 |")
    A("| 8 | §5「8-bit 经济性不成立」 | 一致，且给出量化：有效域内平均步长与相邻码值 ΔE | E3 |")

    # ---------------- 9. 硬边界 ----------------
    A("\n## 9. 三条硬边界（比调参更重要）\n")
    A("### 9.1 第三方 App 拿不到 Magic-Log\n")
    A("荣耀的 Magic-Log 是**相机 App 内部的视频模式**，Camera2 暴露给第三方的是 RAW / YUV 流，"
      "不包含那条厂商 Log 曲线。所以「录制 Magic-Log → 实时映射」在第三方 App 里**无法成立**："
      "你录到的是 RAW 或 P010，而不是 Magic-Log。\n")
    A("可行的产品形态只有两条：\n")
    A("1. **文件处理型**：用户用原生相机录 Magic-Log，App 导入视频 → 解码 → 本管线 → 重新编码。"
      "（这是本仓库管线能直接复用的地方，也是精度承诺唯一成立的地方。）\n")
    A("2. **RAW 自建 Log**：Camera2 取 RAW10/12，自己做去马赛克、白平衡、黑电平，"
      "然后**直接编码为 LogC3**（跳过荣耀 LUT，因为根本用不上）。这条路没有任何厂商 LUT 依赖，"
      "数学上更干净，但要自己解决 RAW 视频的帧率与热问题（多数机型 RAW 连拍 < 10fps）。\n")
    A("### 9.2 宽容度损失\n")
    A(f"顶部 {_fmt(e4['lost_stops'], 1)} 档高光在第一步（还原 LUT 输出 709）就被压缩，"
      "属于路线选择带来的固定代价，见 §6。\n")
    A("### 9.3 精度天花板\n")
    A("本报告能证明的是「实现正确」。真实精度取决于三件本仓库**不包含**的东西："
      "荣耀官方还原 LUT、真实 Magic-Log 素材、ARRI LUT。三者到位前，任何 ΔE 数字都不代表 ARRI 保真度。\n")

    # ---------------- 10. 移植 ----------------
    A("\n## 10. 到 Android 的移植对照\n")
    A(_md_table(
        ["阶段", "Python", "GLSL (`glsl/logc3_pipeline.frag`)"],
        [
            ["还原 LUT 采样", "`LUT3D.sample(x, 'tetrahedral')`", "`sampleLut3D(uRestoreLut, …)`"],
            ["OETF 逆", "`transfers.rec709_oetf_inverse`", "`rec709OetfInverse`"],
            ["709→AWG3", "`matrix_709_to_awg3()`（float32 matmul）", "`uM709ToAwg3 * linear709`"],
            ["LogC3 编码", "`transfers.logc3_encode`", "`logc3Encode`（`log2(x)*0.30103`，ES 3.0 无 `log10`）"],
            ["Look LUT 采样", "`LUT3D.sample`", "`sampleLut3D(uLookLut, …)`"],
        ]))
    A("\n纹理约定：`data[r,g,b,3]` 与 `texelFetch(lut, ivec3(r,g,b))` 一一对应，"
      "上传时 `GL_RGB16F` + `glTexImage3D(..., N,N,N, GL_RGB, GL_FLOAT, data)` 即可，"
      "不需要在 CPU 侧做转置。`glsl_mirror.py` 与 numpy 管线的逐像素最大偏差 "
      "**3.6e-7**（即 float32 的 1~2 ULP），回归测试见 "
      "`tests/test_pipeline.py::test_glsl_mirror_equivalence`。\n")
    A("上真机后仍需验证：`highp` 精度限定符是否被驱动降级、`GL_RGB16F` 过滤是否硬件支持、"
      "以及 `log2` 在驱动端的 ULP。这三项 CPU 侧测不出来。\n")

    # ---------------- 11. 第一批真机验证顺序 ----------------
    A("\n## 11. 拿到真实素材后的验证顺序\n")
    A("1. `python -m magicrri info --lut <官方还原LUT>` 确认 size / DOMAIN_MIN / DOMAIN_MAX / 是否 1D。\n"
      "2. `python -m magicrri probe --lut <官方还原LUT>` 用 0..1023 灰阶楔确认输出单调、"
      "并给出真实有效域（哪个输入码值处开始有输出、哪个码值处到顶）。\n"
      "3. 拍灰卡（多 ISO）确认黑电平：还原 LUT 输出的最暗块应落在 0 附近且三通道一致。\n"
      "4. 再跑色卡 ΔE。先看 ΔL* 再看 ΔC*：ΔL 系统性偏差通常来自解码曲线选错，"
      "ΔC 系统性偏高通常来自漏掉色域矩阵。\n"
      "5. 最后才动 Look LUT。\n")

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")


def _row(e2, key) -> Dict[str, Any]:
    for r in e2["rows"]:
        if r["key"] == key:
            return r
    raise KeyError(key)


# ==========================================================================
# 主流程
# ==========================================================================

def run(out_dir: str = "report") -> Dict[str, Any]:
    import sys

    import numpy as np

    from . import __version__

    paths = _ensure(out_dir)
    ctx = build_context(33)

    e0 = exp0_math()
    e1 = exp1_fidelity(ctx)
    e2 = exp2_ablations(ctx)
    e3 = exp3_banding(ctx)
    e4 = exp4_dynamic_range(ctx)
    e5 = exp5_lut_precision(ctx)

    # 图
    fig_transfers(os.path.join(paths["figures"], "f1_transfers.png"))
    fig_tone_curve(ctx, os.path.join(paths["figures"], "f2_tone_curve.png"))
    fig_delta_e(e1, e2, os.path.join(paths["figures"], "f3_delta_e.png"))
    fig_ab_scatter(ctx, e1, os.path.join(paths["figures"], "f4_ab_scatter.png"))
    fig_banding(e3, os.path.join(paths["figures"], "f5_banding.png"))

    # 端到端对照图（码值直接写入 PNG）
    _save_png(os.path.join(paths["images"], "01_input_magiclog.png"),
              fixtures.render_chart(ctx["log_in"]))
    _save_png(os.path.join(paths["images"], "02_out_baseline.png"),
              fixtures.render_chart(make_pipe(ctx)(ctx["log_in"])))
    _save_png(os.path.join(paths["images"], "03_out_no_oetf_inverse.png"),
              fixtures.render_chart(make_pipe(ctx, decode_mode="none")(ctx["log_in"])))
    _save_png(os.path.join(paths["images"], "04_out_no_gamut.png"),
              fixtures.render_chart(make_pipe(ctx, gamut_convert=False)(ctx["log_in"])))
    _save_png(os.path.join(paths["images"], "05_out_arri_like_look.png"),
              fixtures.render_chart(
                  make_pipe(ctx, look_lut=ctx["look_arri_like"])(ctx["log_in"])))

    # 合成 LUT（可被真实文件替换）
    cube.write_cube(ctx["restore33"],
                    os.path.join(paths["luts"], "synthetic_restore_v2_33.cube"))
    cube.write_cube(ctx["look33"],
                    os.path.join(paths["luts"], "synthetic_arri_neutral_33.cube"))
    with open(os.path.join(paths["luts"], "README.txt"), "w", encoding="utf-8") as fh:
        fh.write(
            "这些 .cube 是**合成替身**，由 magicrri/curve.py 按公开数学参数生成。\n"
            "它们不含任何荣耀或 ARRI 的受权资产，可以随代码一起分发。\n\n"
            "替换为真实资产的方法：\n"
            "  python -m magicrri apply --restore <官方还原LUT>.cube --look <ARRI LUT>.cube \\\n"
            "      --in in.png --out out.png\n"
            "或用 Python API：pipeline.PipelineConfig(restore_lut=read_cube(...), look_lut=read_cube(...))\n"
        )

    meta = {"version": __version__, "numpy": np.__version__, "python": sys.version.split()[0]}
    result = {"meta": meta, "E0": e0, "E1": e1, "E2": e2, "E3": e3, "E4": e4, "E5": e5}
    with open(os.path.join(out_dir, "metrics.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
    write_report(os.path.join(out_dir, "report.md"), e0, e1, e2, e3, e4, e5, meta)
    return result


def main(argv=None) -> int:
    import argparse

    ap = argparse.ArgumentParser(prog="magicrri verify", description="生成验证报告")
    ap.add_argument("--out", default="report", help="输出目录")
    args = ap.parse_args(argv)
    res = run(args.out)
    e1 = res["E1"]
    print(f"[ok] report -> {args.out}")
    print(f"     E1 ΔE mean={e1['stats']['mean']:.3f} max={e1['stats']['max']:.3f} "
          f"skin={e1['skin_mean']:.3f}")
    for r in res["E2"]["rows"][1:]:
        print(f"     E2 {r['key']:<22} ΔE={r['dE_vs_baseline_mean']:7.2f}  "
              f"chroma×{r['chroma_ratio']:.3f}")
    return 0
