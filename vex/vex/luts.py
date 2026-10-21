# -*- coding: utf-8 -*-
"""LUT 资产注册表：内置路径、来源与使用约束。

本仓库自带的 LUT 全部来自厂商**官方**发布页，并记录来源与抓取日期：

* 荣耀 Magic-Log → Rec.709 还原 LUT（v1 / v2，33³）
  来源：https://www.honor.com/cn/phones/rec-lut-download/
* ARRI LogC3 → Rec.709 (Gamma 2.4) LUT 包 v1-2-1（33³ / 65³）
  来源：https://www.arri.com/en/learn/camera-systems/tools/lut-generator

两者都是厂商受权资产：可以本地使用，**不要随代码再分发**。
因此这些文件在 ``.gitignore`` 里被排除，仓库只保留本说明。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Dict, List, Optional

from .cube import LUT3D, CurveLUT, read_cube

__all__ = [
    "LutAsset",
    "ASSET_ROOT",
    "RESTORE_LUTS",
    "LOOK_LUTS",
    "load_asset",
    "describe_inventory",
    "verify_assets",
]

ASSET_ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets", "luts")

HONOR_DIR = os.path.join(ASSET_ROOT, "honor")
ARRI_DIR = os.path.join(ASSET_ROOT, "arri", "pkg", "ARRI_LogC3_v1-2-1_LUT_Package", "LUTs")


@dataclass(frozen=True)
class LutAsset:
    key: str
    label: str
    path: str
    kind: str          # "restore" | "look"
    note: str = ""
    requires: str = ""  # 适用机型/条款说明

    def exists(self) -> bool:
        return os.path.isfile(self.path)


#: Magic-Log → Rec.709 还原 LUT（荣耀官方）
RESTORE_LUTS: Dict[str, LutAsset] = {
    "v1": LutAsset(
        "v1",
        "Magic-Log v1 → Rec.709",
        os.path.join(HONOR_DIR, "honor_logv1_rec709_v3_33.cube"),
        "restore",
        "荣耀官方发布，33³",
        "Magic3 系列 / Magic V / Magic4 / Magic5 / Magic Vs / Magic V2",
    ),
    "v2": LutAsset(
        "v2",
        "Magic-Log v2 → Rec.709",
        os.path.join(HONOR_DIR, "honor_new_logv2_rec709_v9.cube"),
        "restore",
        "荣耀官方发布，33³",
        "Magic4 Pro+ / Magic5 Pro+ / Magic6–Magic8 / Magic V3 / V5",
    ),
}

#: LogC3/AWG3 → Rec.709 显示（Gamma 2.4）LUT（ARRI 官方）
LOOK_LUTS: Dict[str, LutAsset] = {
    "standard": LutAsset(
        "standard",
        "ARRI LogC3 → Rec.709 (标准)",
        os.path.join(ARRI_DIR, "ARRI_LogC3-to-Gamma24_Rec709_D65-v1_33.cube"),
        "look",
        "ARRI 官方 v1，33³，输出 gamma 2.4",
    ),
    "classic": LutAsset(
        "classic",
        "ARRI LogC3 → Rec.709 (Classic)",
        os.path.join(ARRI_DIR, "ARRI_LogC3-to-Gamma24_Rec709_D65-Classic_33.cube"),
        "look",
        "ARRI 官方 Classic 风格，33³，输出 gamma 2.4",
    ),
    "rec2020": LutAsset(
        "rec2020",
        "ARRI LogC3 → Rec.2020 (标准)",
        os.path.join(ARRI_DIR, "ARRI_LogC3-to-Gamma24_Rec2020_D65-v1_33.cube"),
        "look",
        "ARRI 官方，Rec.2020 基色，33³，输出 gamma 2.4",
    ),
}


def load_asset(key: str, kind: Optional[str] = None) -> "LUT3D | CurveLUT":
    """按 key 载入内置 LUT；key 也可以是任意 .cube 路径。"""
    if os.path.isfile(key):
        return read_cube(key)
    table = RESTORE_LUTS if kind == "restore" else LOOK_LUTS if kind == "look" else {**RESTORE_LUTS, **LOOK_LUTS}
    if key not in table:
        raise KeyError(f"未知 LUT: {key}（可选: {', '.join(sorted(table))}）")
    asset = table[key]
    if not asset.exists():
        raise FileNotFoundError(
            f"缺少 LUT 文件: {asset.path}\n"
            "请先运行 `python -m vex assets fetch` 或手动放入（来源见 assets/luts/SOURCES.md）"
        )
    return read_cube(asset.path)


def verify_assets() -> List[dict]:
    """逐项检查内置 LUT 是否存在、可解析，并给出灰轴诊断。"""
    rows = []
    for table in (RESTORE_LUTS, LOOK_LUTS):
        for key, asset in table.items():
            row = {"key": key, "kind": asset.kind, "path": asset.path, "exists": asset.exists()}
            if asset.exists():
                try:
                    lut = read_cube(asset.path)
                    d = lut.describe()
                    row.update({
                        "size": d.get("size"),
                        "domain": [d.get("domain_min"), d.get("domain_max")],
                        "gray_spread": d.get("gray_axis_channel_spread"),
                        "gray_shift": d.get("gray_axis_mean_shift"),
                        "ok": True,
                    })
                except Exception as exc:  # pragma: no cover - 诊断用
                    row.update({"ok": False, "error": str(exc)})
            rows.append(row)
    return rows


def describe_inventory() -> str:
    lines = ["还原 LUT（Magic-Log → Rec.709）:"]
    for key, a in RESTORE_LUTS.items():
        mark = "✓" if a.exists() else "✗"
        lines.append(f"  {mark} {key:<8} {a.label}   {a.requires}")
    lines.append("Look LUT（LogC3/AWG3 → Rec.709 gamma2.4）:")
    for key, a in LOOK_LUTS.items():
        mark = "✓" if a.exists() else "✗"
        lines.append(f"  {mark} {key:<8} {a.label}   {a.note}")
    return "\n".join(lines)
