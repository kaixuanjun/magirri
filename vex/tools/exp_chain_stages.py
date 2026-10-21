"""把这一帧直接推进完整链路（Python），看是不是链路本身的问题。"""
import subprocess

import numpy as np

from vex import bake, luts


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


def stats(a, name):
    y = a.mean(2)
    print(f'{name:26s} mean={y.mean():.3f} std={y.std():.3f} '
          f'p1={np.percentile(y,1):.3f} p99={np.percentile(y,99):.3f}')


img = frame('VID_20261020_103942.mp4')
save(img, 'samples/c_0_input.png')
stats(img, '输入')

for ver, look in (('v2', 'standard'), ('v1', 'standard'), ('v2', 'classic')):
    cfg = bake.ChainConfig(
        restore=luts.load_asset(ver), look=luts.load_asset(look), size=65
    )
    r = bake.evaluate_chain(cfg, img)
    stats(r['output'], f'链路 {ver}/{look}')
    save(r['output'], f'samples/c_{ver}_{look}.png')

# 每一级的对比度，定位是哪一级把画面压平的
cfg = bake.ChainConfig(restore=luts.load_asset('v2'), look=luts.load_asset('standard'), size=65)
r = bake.evaluate_chain(cfg, img)
for k in ('restore', 'linear', 'linear_awg3', 'logc3', 'output'):
    stats(np.clip(r[k], 0, 1), '  ' + k)
