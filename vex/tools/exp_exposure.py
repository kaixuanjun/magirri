"""验证：手机 Log 的码值范围比 ARRI LUT 期望的窄，补一点曝光（Log 码值加偏置）就能出正常对比。"""
import subprocess

import numpy as np

from vex import bake, luts


def frame(path='VID_20261020_103942.mp4', w=360):
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


img = frame()
cfg = bake.ChainConfig(restore=luts.load_asset('v2'), look=luts.load_asset('standard'), size=65)

for delta in (0.0, 0.06, 0.12, 0.18, 0.24):
    shifted = np.clip(img + delta, 0.0, 1.0)
    out = bake.evaluate_chain(cfg, shifted)['output']
    y = out.mean(2)
    print(f'delta=+{delta:.2f}  mean={y.mean():.3f} std={y.std():.3f} '
          f'p1={np.percentile(y,1):.3f} p99={np.percentile(y,99):.3f}')
    save(out, f'samples/e_{int(delta*100):02d}.png')

print('已导出 samples/e_00.png … e_24.png')
