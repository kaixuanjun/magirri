"""检查 709↔AWG3 矩阵：Rec.709 的色域完全在 AWG3 内，转换不该出现负值。"""
import numpy as np

from vex.color import matrix_709_to_awg3, matrix_awg3_to_709

# ARRI 官方给出的 AWG3 → Rec.709 矩阵
ARRI_AWG3_TO_709 = np.array([
    [1.70505, -0.62179, -0.08326],
    [-0.13026, 1.14080, -0.01054],
    [-0.02400, -0.12897, 1.15297],
], dtype=np.float64)

m = matrix_709_to_awg3().astype(np.float64)
print("代码里的 709→AWG3:\n", np.round(m, 5))
print("\nARRI 官方 AWG3→709 的逆（即正确的 709→AWG3）:\n", np.round(np.linalg.inv(ARRI_AWG3_TO_709), 5))

print("\n最大差:", np.abs(m - np.linalg.inv(ARRI_AWG3_TO_709)).max().round(4))

for name, rgb in (("709 红", (1, 0, 0)), ("709 绿", (0, 1, 0)), ("709 蓝", (0, 0, 1)), ("白", (1, 1, 1))):
    a = m @ np.array(rgb, float)
    b = np.linalg.inv(ARRI_AWG3_TO_709) @ np.array(rgb, float)
    print(f"{name:6s}  代码: {np.round(a, 4)}   正确: {np.round(b, 4)}")

print("\n代码里的 AWG3→709:\n", np.round(matrix_awg3_to_709().astype(np.float64), 5))
