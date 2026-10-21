"""HLG → SDR 的几种映射，哪个能把 HLG 素材还原成原始 SDR 画面。

hlg_real.mp4 是用 zscale 从 test_magiclog_h264.mp4 编出来的，
所以「SDR 原帧」就是标准答案。
"""
import subprocess

import numpy as np


def frame(path):
    cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-ss', '1.0', '-i', path,
           '-frames:v', '1', '-vf', 'format=rgb24', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    a = np.frombuffer(subprocess.run(cmd, capture_output=True).stdout, np.uint8)
    return (a.astype(np.float32) / 255.0).reshape(-1, 3)


def oetf709(x):
    return np.clip(np.where(x <= 0.018, x * 4.5,
                            1.099 * np.power(np.maximum(x, 0), 0.45) - 0.099), 0, 1)


sdr = frame('samples/test_magiclog_h264.mp4')
h = frame('samples/hlg_real.mp4')

e = np.where(h <= 0.5, h * h / 3.0,
             (np.exp((h - 0.55991073) / 0.17883277) + 0.28466892) / 12.0)
w = (np.exp((0.75 - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
print('invOETF(0.75) = %.4f' % w)

variants = {
    'A 仅反OETF -> 709': oetf709(e),
    'B e^1.2 -> 709': oetf709(np.power(np.maximum(e, 0), 1.2)),
    'C e^1.2*4.93 -> 709 (当前)': oetf709(np.power(np.maximum(e, 0), 1.2) * 4.926),
    'D e/0.265 -> 709': oetf709(e / w),
    'E e/0.265 线性直出': np.clip(e / w, 0, 1),
}
for k, v in variants.items():
    print('%-28s mean=%.3f std=%.3f max=%.3f  |diff vs SDR|=%.4f'
          % (k, v.mean(), v.std(), v.max(), np.abs(v - sdr).mean()))

print('%-28s mean=%.3f std=%.3f max=%.3f' % ('SDR 目标', sdr.mean(), sdr.std(), sdr.max()))
