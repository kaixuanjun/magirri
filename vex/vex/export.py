# -*- coding: utf-8 -*-
"""ffmpeg 封装：探测素材、抽帧预览、套烘焙好的 LUT 导出。

设计取舍
--------
* **不在 Python 里逐帧处理视频**：链已经烘焙成一个 3D LUT，交给 ffmpeg 的
  ``lut3d``（四面体插值），4K 也能跑满速。
* **保住位深**：10-bit 素材走 ``format=rgb48le`` → LUT → ``yuv420p10le``，
  中间不做 8-bit 量化。8-bit 素材才走 ``rgb24``。
* **不猜色彩矩阵**：RGB→YUV 用显式 ``out_color_matrix=bt709``，
  输出统一打 bt709 标记（ARRI 的 SDR LUT 本来就是给 709 显示器用的）。
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

__all__ = [
    "MediaInfo",
    "probe",
    "read_frame",
    "build_command",
    "export",
    "compare_sheet",
    "FFmpegMissing",
]


class FFmpegMissing(RuntimeError):
    pass


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if not exe:
        raise FFmpegMissing("找不到 ffmpeg，请先安装并加入 PATH")
    return exe


def _ffprobe() -> str:
    exe = shutil.which("ffprobe")
    if not exe:
        raise FFmpegMissing("找不到 ffprobe，请先安装并加入 PATH")
    return exe


# --------------------------------------------------------------------------
# 探测
# --------------------------------------------------------------------------

@dataclass
class MediaInfo:
    path: str
    width: int
    height: int
    fps: float
    codec: str
    pix_fmt: str
    bit_depth: int
    duration: float
    has_audio: bool
    audio_codec: str = ""
    color_space: str = ""
    color_transfer: str = ""
    color_primaries: str = ""
    color_range: str = ""
    rotation: int = 0

    def describe(self) -> str:
        return (f"{self.width}x{self.height} {self.fps:.3f}fps {self.codec} "
                f"{self.pix_fmt}({self.bit_depth}bit) {self.duration:.2f}s "
                f"audio={'yes:' + self.audio_codec if self.has_audio else 'no'}")


def probe(path: str) -> MediaInfo:
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    cmd = [_ffprobe(), "-v", "error", "-print_format", "json",
           "-show_format", "-show_streams", path]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if out.returncode != 0:
        raise RuntimeError(f"ffprobe 失败: {out.stderr.strip()[:400]}")
    data = json.loads(out.stdout or "{}")
    streams = data.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    if v is None:
        raise RuntimeError("文件里没有视频流")
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)

    fps = 0.0
    for key in ("avg_frame_rate", "r_frame_rate"):
        raw = v.get(key) or "0/0"
        try:
            num, den = raw.split("/")
            if float(den) > 0:
                fps = float(num) / float(den)
                if fps > 0:
                    break
        except Exception:
            continue

    pix = v.get("pix_fmt", "") or ""
    bit_depth = 10 if re.search(r"(10|12|16)(le|be)?$", pix) else 8
    rot = 0
    for sd in v.get("side_data_list", []) or []:
        if "rotation" in sd:
            try:
                rot = int(float(sd["rotation"]))
            except Exception:
                pass

    dur = 0.0
    for src in (v.get("duration"), data.get("format", {}).get("duration")):
        try:
            dur = float(src)
            if dur > 0:
                break
        except Exception:
            continue

    return MediaInfo(
        path=path,
        width=int(v.get("width", 0)),
        height=int(v.get("height", 0)),
        fps=fps,
        codec=v.get("codec_name", ""),
        pix_fmt=pix,
        bit_depth=bit_depth,
        duration=dur,
        has_audio=a is not None,
        audio_codec=(a or {}).get("codec_name", ""),
        color_space=v.get("color_space", "") or "",
        color_transfer=v.get("color_transfer", "") or "",
        color_primaries=v.get("color_primaries", "") or "",
        color_range=v.get("color_range", "") or "",
        rotation=rot,
    )


def read_frame(path: str, at: float = 0.0, max_width: Optional[int] = None) -> np.ndarray:
    """抽一帧，返回 (H,W,3) float32，值域 0..1，按 16-bit 解码（避免 8-bit 截断）。

    ``-ss`` 放在 ``-i`` **之后**（精确 seek）：逐帧解码到位，保证对比两张图时
    取到同一帧；放在前面会按关键帧跳，不同 GOP 结构会错位。
    """
    cmd = [_ffmpeg(), "-hide_banner", "-loglevel", "error",
           "-i", path,
           "-ss", f"{max(0.0, float(at)):.3f}",
           "-frames:v", "1"]
    if max_width:
        cmd += ["-vf", f"scale={int(max_width)}:-2:flags=lanczos"]
    cmd += ["-f", "rawvideo", "-pix_fmt", "rgb48le", "-"]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0 or not proc.stdout:
        raise RuntimeError(f"抽帧失败: {proc.stderr.decode('utf-8', 'replace')[:300]}")
    raw = np.frombuffer(proc.stdout, dtype="<u2")
    px = raw.size // 3
    if px == 0:
        raise RuntimeError("抽帧返回空数据")
    # 尺寸：由输出字节数反推（宽高比保持不变，宽高都从 probe 拿不准缩放后的值）
    info = probe(path)
    if max_width and info.width > max_width:
        w = int(max_width) - (int(max_width) % 2)
        h = int(round(info.height * w / info.width / 2) * 2)
    else:
        w, h = info.width, info.height
    if w * h != px:
        # 旋转素材会被 ffmpeg autorotate 成竖屏，按宽高比反推，别再按原始宽高算
        if max_width and px % w == 0:
            h = px // w
        elif max_width and px % (int(max_width) - (int(max_width) % 2)) == 0:
            w = int(max_width) - (int(max_width) % 2)
            h = px // w
        if w * h != px:
            raise RuntimeError(f"抽帧尺寸不匹配: 期望 {w}x{h}={w*h}，实得 {px} 像素")
    img = raw.reshape(h, w, 3).astype(np.float32) / 65535.0
    return img


# --------------------------------------------------------------------------
# 导出
# --------------------------------------------------------------------------

@dataclass
class ExportSpec:
    input: str
    output: str
    lut_path: str
    bit_depth: int = 10
    codec: str = ""            # 空 = 按位深自动
    crf: int = 18
    preset: str = "medium"
    audio: str = "auto"        # auto | copy | aac | none
    yuv: str = "420"           # 420 | 422 | 444（4:4:4 / 4:2:2 供后续调色）
    extra_args: List[str] = field(default_factory=list)


def _pick_encoder(spec: ExportSpec) -> Tuple[str, List[str]]:
    codec = spec.codec
    if not codec:
        codec = "hevc" if spec.bit_depth >= 10 else "h264"
    if codec in ("hevc", "h265"):
        return "libx265", ["-crf", str(spec.crf), "-preset", spec.preset,
                           "-tag:v", "hvc1",
                           "-x265-params", "log-level=error:colorprim=bt709:transfer=bt709:colormatrix=bt709"]
    if codec in ("h264", "avc"):
        return "libx264", ["-crf", str(spec.crf), "-preset", spec.preset,
                           "-profile:v", "high"]
    if codec in ("nvenc", "hevc_nvenc"):
        return "hevc_nvenc", ["-preset", "p5", "-tune", "hq", "-rc", "vbr",
                              "-cq", str(spec.crf), "-b:v", "0", "-tag:v", "hvc1"]
    if codec in ("h264_nvenc",):
        return "h264_nvenc", ["-preset", "p5", "-tune", "hq", "-rc", "vbr",
                              "-cq", str(spec.crf), "-b:v", "0"]
    raise ValueError(f"不支持的编码器: {codec}")


def build_command(spec: ExportSpec, info: MediaInfo, lut_ref: Optional[str] = None) -> List[str]:
    """拼 ffmpeg 命令。

    ``lut_ref`` 是写进滤镜图的 LUT 文件名。**不要**直接塞绝对路径：
    Windows 的 ``C:\\...`` 会被滤镜图解析器当成选项分隔符，报
    ``No option name near '\\Users...'``。正确做法是把 LUT 复制到工作目录、
    只写文件名，并让 ffmpeg 的 cwd 指向那里（见 :func:`export`）。
    """
    pix_in = "rgb48le" if spec.bit_depth >= 10 else "rgb24"
    chroma = {"420": "420", "422": "422", "444": "444"}.get(str(spec.yuv), "420")
    if spec.bit_depth >= 10:
        pix_out = f"yuv{chroma}p10le"
    else:
        pix_out = f"yuv{chroma}p"
    flags = "accurate_rnd+full_chroma_int" + ("+full_chroma_inp" if spec.bit_depth >= 10 else "")
    lut_name = lut_ref or os.path.basename(spec.lut_path)
    vf = (
        f"format={pix_in},"
        f"lut3d=file={lut_name}:interp=tetrahedral,"
        f"scale=out_color_matrix=bt709:out_range=tv:flags={flags},"
        f"format={pix_out}"
    )
    enc, enc_args = _pick_encoder(spec)
    cmd = [_ffmpeg(), "-hide_banner", "-loglevel", "error", "-nostats", "-y",
           "-i", os.path.abspath(spec.input), "-map", "0:v:0"]
    if info.has_audio and spec.audio != "none":
        cmd += ["-map", "0:a:0"]
    cmd += ["-vf", vf, "-c:v", enc, *enc_args,
            "-color_primaries", "bt709", "-color_trc", "bt709",
            "-colorspace", "bt709", "-color_range", "tv",
            "-movflags", "+faststart", "-map_metadata", "0"]
    if info.has_audio and spec.audio != "none":
        audio_codec = info.audio_codec.lower()
        if spec.audio == "aac" or audio_codec not in ("aac", "mp3", "ac3", "eac3", "alac", "opus"):
            cmd += ["-c:a", "aac", "-b:a", "192k"]
        else:
            cmd += ["-c:a", "copy"]
    else:
        cmd += ["-an"]
    cmd += list(spec.extra_args)
    cmd += [os.path.abspath(spec.output)]
    return cmd




def export(spec: ExportSpec, info: Optional[MediaInfo] = None,
           on_progress: Optional[Callable[[float, str], None]] = None,
           dry_run: bool = False) -> List[str]:
    info = info or probe(spec.input)

    # 把 LUT 放在一个干净的临时工作目录里，用文件名引用，绕开路径转义问题
    workdir = tempfile.mkdtemp(prefix="mrr_export_")
    local_lut = os.path.join(workdir, "chain.cube")
    shutil.copyfile(spec.lut_path, local_lut)

    # 自动亮度/对比度：ARRI 的曲线把高光留给后期，手机 Log 套完只到 ~0.7，整片发灰。

    cmd = build_command(spec, info, lut_ref="chain.cube")
    if dry_run:
        shutil.rmtree(workdir, ignore_errors=True)
        return cmd
    total = max(1e-6, info.duration)
    try:
        proc = subprocess.Popen(
            cmd + ["-progress", "pipe:1"],
            cwd=workdir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            encoding="utf-8", errors="replace",
        )
        # stderr 必须**并发**读走：只读 stdout 时，stderr 管道写满会让 ffmpeg
        # 卡死、而我们在等 stdout —— 8-bit 那次十分钟挂住就是这么来的。
        err_lines: List[str] = []

        def _drain() -> None:
            if proc.stderr is not None:
                for line in proc.stderr:
                    err_lines.append(line)

        reader = threading.Thread(target=_drain, daemon=True)
        reader.start()

        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.strip()
            if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
                _, _, val = line.partition("=")
                try:
                    # 注意：ffmpeg 的 out_time_ms 是历史误名，实际单位也是微秒
                    seconds = float(val) / 1e6
                except ValueError:
                    continue
                frac = min(1.0, seconds / total)
                if on_progress:
                    on_progress(frac, f"{seconds:6.1f}s / {info.duration:6.1f}s")
        rc = proc.wait()
        reader.join(timeout=2.0)
        if rc != 0:
            raise RuntimeError("ffmpeg 失败 (exit %d):\n%s" % (rc, "".join(err_lines)[-1500:]))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    if on_progress:
        on_progress(1.0, "done")
    return cmd


# --------------------------------------------------------------------------
# 对照图
# --------------------------------------------------------------------------

def _font(size: int):
    from PIL import ImageFont

    for name in ("msyh.ttc", "msyhbd.ttc", "simhei.ttf", "arial.ttf"):
        path = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", name)
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def compare_sheet(frame: np.ndarray, panels: Sequence[Tuple[str, np.ndarray]],
                  path: str, tile_width: int = 520, gap: int = 8,
                  label_height: int = 34) -> str:
    """把若干「同一帧的不同处理结果」拼成对照图，便于肉眼选参数。"""
    from PIL import Image, ImageDraw

    imgs = []
    for label, arr in panels:
        a = np.clip(arr, 0.0, 1.0)
        h, w = a.shape[:2]
        scale = tile_width / float(w)
        nw, nh = int(tile_width), max(1, int(round(h * scale)))
        im = Image.fromarray((a * 255.0 + 0.5).astype(np.uint8), mode="RGB").resize((nw, nh), Image.LANCZOS)
        imgs.append((label, im))

    cols = 2 if len(imgs) > 1 else 1
    rows = (len(imgs) + cols - 1) // cols
    tw = imgs[0][1].width
    th = imgs[0][1].height
    W = cols * tw + (cols + 1) * gap
    H = rows * (th + label_height) + (rows + 1) * gap
    sheet = Image.new("RGB", (W, H), (18, 18, 20))
    draw = ImageDraw.Draw(sheet)
    font = _font(16)
    for i, (label, im) in enumerate(imgs):
        r, c = divmod(i, cols)
        x = gap + c * (tw + gap)
        y = gap + r * (th + label_height + gap)
        draw.text((x + 2, y + 6), label, fill=(230, 230, 230), font=font)
        sheet.paste(im, (x, y + label_height))
    sheet.save(path)
    return path