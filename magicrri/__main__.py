# -*- coding: utf-8 -*-
"""命令行入口：``python -m magicrri <command>``。

主流程（新方案）
----------------
    export    选一个 Log 视频 → 套还原 LUT + ARRI LUT → 导出成片
    compare   抽一帧，把几种解码假设并排画出来，30 秒内定参数
    bake      只把整条链烘焙成一个 .cube（可丢进 Resolve/Premiere）
    assets    查看/重新下载官方 LUT

保留的工具
----------
    verify    跑全部实验并生成报告（桌面参考实现那一套）
    info      检查任意 .cube 的头信息
    probe     用灰阶楔标定 LUT 的有效域
    apply     对单张图片套用管线
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np


# --------------------------------------------------------------------------
# 工具
# --------------------------------------------------------------------------

def _load_lut(path: str, swap_rb: bool = False):
    from .cube import read_cube

    lut = read_cube(path)
    if swap_rb:
        if not hasattr(lut, "permuted"):
            raise SystemExit("--swap-rb 只对 3D LUT 有效（1D 曲线没有 R/B 轴顺序问题）")
        lut = lut.permuted()
    return lut


def _identity_lut():
    from .cube import LUT3D

    return LUT3D.identity(33)


def _read_png_rgb(path: str) -> np.ndarray:
    from PIL import Image

    img = Image.open(path)
    if img.mode != "RGB":
        img = img.convert("RGB")
    return np.asarray(img, dtype=np.float32) / 255.0


def _write_png_rgb(path: str, rgb01) -> None:
    from PIL import Image

    arr = (np.clip(np.asarray(rgb01, dtype=np.float32), 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
    Image.fromarray(arr, mode="RGB").save(path)


def _chain_from_args(args):
    from .bake import ChainConfig
    from .luts import load_asset

    restore = load_asset(args.log, "restore")
    look = load_asset(args.look, "look")
    return ChainConfig(
        restore=restore,
        look=look,
        mid_transfer=args.mid_transfer,
        gamut_convert=not args.no_gamut,
        output_transfer=args.output_transfer,
        strength=args.strength,
        size=args.size,
        finish_look=not getattr(args, "no_look", False),
    )


def _fmt_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{int(n)}B" if unit == "B" else f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}GB"


class _Bar:
    def __init__(self, width: int = 28):
        self.width = width
        self.last = -1

    def __call__(self, frac: float, text: str) -> None:
        pct = int(max(0.0, min(1.0, frac)) * 100)
        if pct <= self.last:
            return
        self.last = pct
        filled = int(self.width * pct / 100)
        bar = "#" * filled + "-" * (self.width - filled)
        sys.stdout.write(f"\r  [{bar}] {pct:3d}%  {text}   ")
        sys.stdout.flush()
        if pct >= 100:
            sys.stdout.write("\n")
            sys.stdout.flush()


# --------------------------------------------------------------------------
# assets
# --------------------------------------------------------------------------

HONOR_BASE = "https://www-file.honor.com/content/dam/honor/cn/rec-lut-download/img/"
ARRI_ZIP = ("https://arri.canto.de/direct/other/0gk2u4aiep401b1rqfgatlqn7v/"
            "79YhGj_75U5GnJMW6Q3KJn3qYe4/original?content-type=application%2Fzip"
            "&name=ARRI+-+LogC3+LUT+Package_data.zip")


def cmd_assets(args) -> int:
    from . import luts

    if args.action == "fetch":
        return _fetch_assets(force=args.force)

    print(luts.describe_inventory())
    print()
    rows = luts.verify_assets()
    bad = [r for r in rows if not r.get("ok")]
    for r in rows:
        if r.get("ok"):
            print(f"  ok  {r['kind']:<8}{r['key']:<10} {r['size']}³ "
                  f"domain={r['domain'][0]}..{r['domain'][1]}  "
                  f"灰轴互差={r['gray_spread']:.5f}")
        else:
            print(f"  缺失  {r['kind']:<8}{r['key']:<10} {r['path']}")
    print(f"\n{len(rows) - len(bad)}/{len(rows)} 个 LUT 就绪")
    return 0 if not bad else 1


def _fetch_assets(force: bool = False) -> int:
    import urllib.request
    import zipfile

    from . import luts

    os.makedirs(luts.HONOR_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(luts.ARRI_DIR), exist_ok=True)
    hdr = {"User-Agent": "Mozilla/5.0",
           "Referer": "https://www.honor.com/cn/phones/rec-lut-download/"}

    def _get(url: str, dst: str, headers=None) -> None:
        tmp = dst + ".part"
        req = urllib.request.Request(url, headers=headers or hdr)
        with urllib.request.urlopen(req, timeout=180) as resp, open(tmp, "wb") as fh:
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                fh.write(chunk)
                done += len(chunk)
                if total:
                    sys.stdout.write(f"\r  {os.path.basename(dst)}: {done * 100 // total}%   ")
                    sys.stdout.flush()
        if total:
            sys.stdout.write("\n")
        os.replace(tmp, dst)

    for key, asset in luts.RESTORE_LUTS.items():
        name = os.path.basename(asset.path)
        if asset.exists() and not force:
            print(f"  已存在，跳过: {name}")
            continue
        print(f"  下载荣耀还原 LUT [{key}]: {name}")
        _get(HONOR_BASE + name, asset.path)

    pkg_root = os.path.dirname(os.path.dirname(luts.ARRI_DIR))
    zip_path = os.path.join(pkg_root, "ARRI_LogC3_LUT_Package.zip")
    if os.path.isdir(luts.ARRI_DIR) and not force:
        print("  ARRI 包已解压，跳过")
    else:
        print("  下载 ARRI LogC3 LUT 包（约 46MB）")
        _get(ARRI_ZIP, zip_path, headers={
            "User-Agent": "Mozilla/5.0",
            "Referer": "https://www.arri.com/en/learn/camera-systems/tools/lut-generator"})
        print("  解压中")
        with zipfile.ZipFile(zip_path) as zf:
            zf.extractall(pkg_root)
    print()
    return cmd_assets(argparse.Namespace(action="list", force=False))


# --------------------------------------------------------------------------
# export
# --------------------------------------------------------------------------

def cmd_export(args) -> int:
    import time

    from . import bake
    from . import export as ex

    info = ex.probe(args.input)
    print(f"输入 : {args.input}")
    print(f"       {info.describe()}  rot={info.rotation}° "
          f"tags={info.color_primaries or '-'}/{info.color_transfer or '-'}/{info.color_space or '-'}")
    if info.color_space and info.color_space not in ("bt709", "unknown", ""):
        print(f"       [注意] 素材标的是 {info.color_space}，而本工具按 bt709 解释；"
              "非 709 素材（如 BT.601 SD）颜色会偏。")
    if info.color_transfer and info.color_transfer not in ("bt709", "unknown", "", "iec61966-2-1"):
        print(f"       [注意] 素材传输函数是 {info.color_transfer}——这是 HDR/非 709 素材，"
              "本工具走 SDR 链，需要先自行转换。")

    cfg = _chain_from_args(args)
    print(f"链   : 还原 LUT[{args.log}] → mid={cfg.mid_transfer} → LogC3 → "
          f"ARRI[{args.look}]  (out={cfg.output_transfer}, strength={cfg.strength:g})")

    bits = args.bits
    if bits == "auto":
        bits = 10 if info.bit_depth >= 10 else 8
    bits = int(bits)

    tmpdir = None
    if args.keep_lut:
        lut_path = args.keep_lut
        parent = os.path.dirname(os.path.abspath(lut_path))
        os.makedirs(parent, exist_ok=True)
    else:
        import tempfile as _tf

        tmpdir = _tf.mkdtemp(prefix="magicrri_")
        lut_path = os.path.join(tmpdir, "chain.cube")

    t0 = time.time()
    print(f"烘焙 : {cfg.size}³ LUT → {lut_path}")
    bake.write_baked(cfg, lut_path, title=f"magicrri chain {cfg.mid_transfer}->{cfg.output_transfer}")
    print(f"       用时 {time.time() - t0:.1f}s")

    spec = ex.ExportSpec(
        input=args.input, output=args.output, lut_path=lut_path,
        bit_depth=bits, codec=args.codec, crf=args.crf, preset=args.preset,
        audio=args.audio, yuv=args.yuv,
    )
    if args.dry_run:
        print("\nffmpeg 命令：\n  " + " ".join(ex.build_command(spec, info)))
        return 0

    print(f"导出 : {args.output}  ({bits}-bit {args.yuv}, "
          f"{spec.codec or ('hevc' if bits >= 10 else 'h264')}, crf={args.crf})")
    t1 = time.time()
    ex.export(spec, info, on_progress=_Bar())
    size = os.path.getsize(args.output) if os.path.exists(args.output) else 0
    dt = time.time() - t1
    print(f"完成 : {args.output}  {_fmt_bytes(size)}  "
          f"耗时 {dt:.1f}s  ({info.duration / max(dt, 1e-6):.2f}x 实时)")
    if tmpdir:
        import shutil as _sh

        _sh.rmtree(tmpdir, ignore_errors=True)
    return 0


def cmd_compare(args) -> int:
    """抽一帧，把「只还原」和三种解码假设并排画出来。"""
    from . import bake
    from . import export as ex
    from .luts import load_asset

    info = ex.probe(args.input)
    at = args.at if args.at is not None else min(1.0, info.duration * 0.25)
    frame = ex.read_frame(args.input, at=at, max_width=args.width)
    print(f"抽帧 : {at:.2f}s  {frame.shape[1]}x{frame.shape[0]}  ({info.describe()})")

    restore = load_asset(args.log, "restore")
    look = load_asset(args.look, "look")

    panels = [("input: Magic-Log (as recorded)", frame)]

    restored = bake.evaluate_chain(
        bake.ChainConfig(restore=restore, look=_identity_lut(), mid_transfer=args.mid_transfer,
                         strength=1.0, size=args.size),
        frame,
    )
    panels.append(("restore LUT only (no ARRI look)", restored["restore"]))

    for mode in ("bt709", "bt1886", "srgb"):
        cfg = bake.ChainConfig(restore=restore, look=look, mid_transfer=mode,
                               output_transfer=args.output_transfer, strength=1.0, size=args.size)
        tag = "   <-- default" if mode == "bt709" else ""
        panels.append((f"ARRI look, mid={mode}{tag}", bake.evaluate_chain(cfg, frame)["output"]))

    ex.compare_sheet(frame, panels, args.out, tile_width=args.tile)
    print(f"对照 : {args.out}")
    print("怎么看：三张 ARRI 图里选天空不过灰、肤色不过黄、暗部仍有细节的那张。")
    print("        默认 bt709；明显偏亮偏灰换 bt1886（gamma 2.4）；反过来偏薄换 srgb。")
    return 0


# --------------------------------------------------------------------------
# bake
# --------------------------------------------------------------------------

def cmd_bake(args) -> int:
    from . import bake

    cfg = _chain_from_args(args)
    print(f"烘焙 : {cfg.size}³  mid={cfg.mid_transfer} out={cfg.output_transfer} "
          f"strength={cfg.strength:g} gamut={cfg.gamut_convert}")
    bake.write_baked(cfg, args.out, title=f"magicrri chain {cfg.mid_transfer}->{cfg.output_transfer}")
    print(f"完成 : {args.out}  ({os.path.getsize(args.out) / 1e6:.1f} MB)")
    print(f"可给 Resolve/Premiere 用，或 ffmpeg -vf lut3d=file={os.path.basename(args.out)}")
    return 0


# --------------------------------------------------------------------------
# 原有工具命令
# --------------------------------------------------------------------------

def cmd_verify(args) -> int:
    from .verify import main as verify_main

    return verify_main(["--out", args.out])


def cmd_info(args) -> int:
    lut = _load_lut(args.lut, args.swap_rb)
    print(json.dumps(lut.describe(), ensure_ascii=False, indent=2))
    return 0


def cmd_probe(args) -> int:
    lut = _load_lut(args.lut, args.swap_rb)
    n = 1024
    code = np.arange(n, dtype=np.float32) / np.float32(n - 1)
    rgb = np.stack([code] * 3, axis=-1)
    out = lut.sample(rgb, "tetrahedral")
    y = out[:, 0]
    mono = int(np.sum(np.diff(y) < -1e-6))
    first = int(np.argmax(y > 1e-5)) if (y > 1e-5).any() else -1
    sat = int(np.argmax(y >= 0.9995)) if (y >= 0.9995).any() else n - 1
    spread = float(np.max(np.abs(out - out.mean(axis=1, keepdims=True))))
    print(f"LUT               : {os.path.basename(args.lut)}  size={lut.size}")
    print(f"输出码值范围      : {float(y.min()) * 1023:.0f} .. {float(y.max()) * 1023:.0f}")
    print(f"首个非零输出码值  : {first}")
    print(f"开始饱和的输入码值: {sat}")
    print(f"单调性            : 非单调点 {mono} 个")
    print(f"三通道互差（最大）: {spread:.6f}")
    if args.out:
        grid = np.tile(code.reshape(1, -1, 1), (64, 1, 3))
        _write_png_rgb(args.out, grid)
        print(f"灰阶楔已写出: {args.out}")
    return 0


def cmd_apply(args) -> int:
    from . import pipeline

    restore = _load_lut(args.restore, args.swap_rb)
    look = _load_lut(args.look, args.swap_look_rb) if args.look else _identity_lut()
    img = _read_png_rgb(args.input)
    cfg = pipeline.PipelineConfig(
        restore_lut=restore, look_lut=look, decode_mode=args.decode,
        gamut_convert=not args.no_gamut, interp=args.interp,
        input_bits=args.bits, output_bits=args.bits,
    )
    out = pipeline.Pipeline(cfg).apply(img)
    _write_png_rgb(args.out, out["output"])
    print(f"[ok] {args.input} -> {args.out}")
    return 0


# --------------------------------------------------------------------------
# 解析
# --------------------------------------------------------------------------

def _add_chain_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--log", default="v2", help="还原 LUT：v1 / v2 / 或 .cube 路径（默认 v2）")
    p.add_argument("--look", default="standard",
                   help="ARRI Look LUT：standard / classic / rec2020 / 或 .cube 路径（默认 standard）")
    p.add_argument("--mid-transfer", default="bt709", choices=["bt1886", "bt709", "srgb"],
                   help="还原 LUT 输出的解码曲线（默认 bt709 = BT.709 OETF 逆；"
                        "厂商没写明时用 compare 三个都看一眼）")
    p.add_argument("--output-transfer", default="keep", choices=["keep", "bt709", "bt1886", "srgb"],
                   help="最终输出编码（默认 keep：保留 ARRI 的 gamma 2.4）")
    p.add_argument("--strength", type=float, default=1.0, help="ARRI 风格强度 0..1（默认 1.0）")
    p.add_argument("--no-look", action="store_true",
                   help="不套仿阿莱成品影调，保留 ARRI 官方转换的原始响应（对比用）")
    p.add_argument("--no-gamut", action="store_true", help="跳过 709->AWG3 基色变换（不建议）")
    p.add_argument("--size", type=int, default=65, help="烘焙网格尺寸（默认 65）")


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="magicrri", description="Log 视频一键还原 + ARRI 调色")
    sub = ap.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("export", help="选视频 → 套还原 LUT + ARRI LUT → 导出")
    e.add_argument("input", help="输入的 Log 视频")
    e.add_argument("-o", "--output", required=True, help="输出视频路径")
    _add_chain_args(e)
    e.add_argument("--bits", default="auto", choices=["auto", "8", "10"], help="输出位深（默认 auto）")
    e.add_argument("--codec", default="", choices=["", "h264", "hevc", "nvenc", "h264_nvenc"],
                   help="编码器（默认按位深；nvenc 走显卡硬件编码，快但质量略逊）")
    e.add_argument("--crf", type=int, default=18)
    e.add_argument("--preset", default="medium")
    e.add_argument("--yuv", default="420", choices=["420", "422", "444"],
                   help="色度采样（默认 420；444 供后续调色，也用于精确验证）")
    e.add_argument("--audio", default="auto", choices=["auto", "copy", "aac", "none"])
    e.add_argument("--keep-lut", help="把烘焙好的链 LUT 存到指定路径")
    e.add_argument("--dry-run", action="store_true", help="只打印 ffmpeg 命令")
    e.set_defaults(func=cmd_export)

    c = sub.add_parser("compare", help="抽一帧做参数对照图")
    c.add_argument("input")
    c.add_argument("-o", "--out", default="compare.png")
    _add_chain_args(c)
    c.add_argument("--at", type=float, default=None, help="抽帧时间（秒）")
    c.add_argument("--width", type=int, default=960, help="抽帧宽度")
    c.add_argument("--tile", type=int, default=520, help="每格宽度")
    c.set_defaults(func=cmd_compare)

    b = sub.add_parser("bake", help="把整条链烘焙成一个 .cube")
    b.add_argument("-o", "--out", required=True)
    _add_chain_args(b)
    b.set_defaults(func=cmd_bake)

    a = sub.add_parser("assets", help="查看 / 重新下载官方 LUT")
    a.add_argument("action", nargs="?", default="list", choices=["list", "fetch"])
    a.add_argument("--force", action="store_true")
    a.set_defaults(func=cmd_assets)

    v = sub.add_parser("verify", help="跑全部实验并生成报告")
    v.add_argument("--out", default="report")
    v.set_defaults(func=cmd_verify)

    i = sub.add_parser("info", help="检查 .cube 头信息")
    i.add_argument("--lut", required=True)
    i.add_argument("--swap-rb", action="store_true")
    i.set_defaults(func=cmd_info)

    pr = sub.add_parser("probe", help="用灰阶楔标定 LUT 有效域")
    pr.add_argument("--lut", required=True)
    pr.add_argument("--swap-rb", action="store_true")
    pr.add_argument("--out")
    pr.set_defaults(func=cmd_probe)

    ap_apply = sub.add_parser("apply", help="对图片套用管线")
    ap_apply.add_argument("--restore", required=True)
    ap_apply.add_argument("--look")
    ap_apply.add_argument("--in", dest="input", required=True)
    ap_apply.add_argument("--out", required=True)
    ap_apply.add_argument("--bits", type=int, default=None)
    ap_apply.add_argument("--decode", default="oetf_inverse", choices=["oetf_inverse", "gamma24", "none"])
    ap_apply.add_argument("--no-gamut", action="store_true")
    ap_apply.add_argument("--interp", default="tetrahedral", choices=["tetrahedral", "trilinear"])
    ap_apply.add_argument("--swap-rb", action="store_true")
    ap_apply.add_argument("--swap-look-rb", action="store_true")
    ap_apply.set_defaults(func=cmd_apply)
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())