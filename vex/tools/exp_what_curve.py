"""这条素材到底是什么曲线：把同一帧分别按「Magic-Log 还原」和「HLG 展开」处理，看哪个像正常画面。"""
import subprocess

import numpy as np


def frame(path, w=360):
    cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-ss', '1.0', '-i', path,
           '-frames:v', '1', '-vf', f'scale={w}:-2', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = subprocess.run(cmd, capture_output=True).stdout
    h = len(raw) // (w * 3)
    return (np.frombuffer(raw, np.uint8).astype(np.float32) / 255.0).reshape(h, w, 3)


def save(arr, path):
    h, w, _ = arr.shape
    a8 = np.clip(arr * 255, 0, 255).astype(np.uint8)
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
                    '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}', '-i', '-',
                    path], input=a8.tobytes(), capture_output=True)


def oetf709(x):
    return np.clip(np.where(x <= 0.018, x * 4.5,
                            1.099 * np.power(np.maximum(x, 0), 0.45) - 0.099), 0, 1)


def hlg_to_sdr(c):
    e = np.where(c <= 0.5, c * c / 3.0,
                 (np.exp((c - 0.55991073) / 0.17883277) + 0.28466892) / 12.0)
    return oetf709(np.maximum(e, 0) / 0.265)


def stats(a, name):
    y = a.mean(2)
    print(f'{name:22s} mean={y.mean():.3f} std={y.std():.3f} '
          f'p1={np.percentile(y,1):.3f} p99={np.percentile(y,99):.3f}')


img = frame('VID_20261020_103942.mp4')
stats(img, '原始（按标签解码）')
save(img, 'samples/u_0_raw.png')
save(hlg_to_sdr(img), 'samples/u_1_hlg.png')
stats(hlg_to_sdr(img), 'HLG→SDR 之后')

# 再套一次简单 gamma，看是不是"已经炒熟的 SDR"
save(oetf709(img / 255.0), 'samples/u_2_709oetf.png')
stats(oetf709(img), '当作线性再上 709')
print('已导出 samples/u_0_raw.png / u_1_hlg.png / u_2_709oetf.png')
