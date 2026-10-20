"""自动亮度/对比度：以中灰为支点加对比，高光走软肩（不硬裁）。"""
import subprocess
import numpy as np
from magicrri import bake, luts

KNEE = 0.85

def frame(path, w=200, at=1.0):
    cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-ss', str(at), '-i', path,
           '-frames:v', '1', '-vf', 'scale=%d:-2' % w, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = subprocess.run(cmd, capture_output=True).stdout
    h = len(raw) // (w * 3)
    return (np.frombuffer(raw, np.uint8).astype(np.float32) / 255.0).reshape(h, w, 3)

def save(arr, path):
    h, w, _ = arr.shape
    a8 = np.clip(arr * 255, 0, 255).astype(np.uint8)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
                    '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '%dx%d' % (w, h), '-i', '-',
                    path], input=a8.tobytes(), capture_output=True)

def tone(v, pivot, c, knee=KNEE):
    t = np.maximum(pivot + (v - pivot) * c, 0.0)
    out = np.where(t <= knee, t, knee + (1.0 - knee) * (1.0 - np.exp(-(t - knee) / (1.0 - knee))))
    return np.clip(out, 0.0, 1.0)

def analyse(img, lut):
    out = lut.sample(img.reshape(-1, 3), 'tetrahedral')
    y = out.mean(axis=1)
    p1, med, p99 = (float(np.percentile(y, q)) for q in (1, 50, 99))
    c = float(np.clip(0.80 / max(1e-3, p99 - p1), 1.0, 1.8))
    return out.reshape(img.shape), med, c

def report(x, tag):
    y = x.mean(2)
    print('  %-10s mean=%.3f std=%.3f p1=%.3f p99=%.3f 过曝=%.2f%%' % (
        tag, y.mean(), y.std(), np.percentile(y, 1), np.percentile(y, 99), (y > 0.995).mean() * 100))

for name, path, save_as in (
    ('用户素材', 'VID_20261020_103942.mp4', 'samples/tone_user.png'),
    ('合成图表', 'samples/test_magiclog_h264.mp4', 'samples/tone_chart.png'),
):
    img = frame(path)
    conf = bake.ChainConfig(restore=luts.load_asset('v2'), look=luts.load_asset('standard'), size=65)
    lut = bake.bake_chain(conf, 't')
    out, med, c = analyse(img, lut)
    fixed = tone(out, med, c)
    print('== %s 中位=%.3f 支点对比 c=%.2f' % (name, med, c))
    report(out, '只套LUT')
    report(fixed, '新曲线')
    save(fixed, save_as)
