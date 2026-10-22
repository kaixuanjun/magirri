# MAGIRRI

两件产品，一个仓库，并列放：

```
magicrri/
├── vex/     MAGIRRI Vex —— Log 视频还原 + ARRI 调色（桌面 CLI + 安卓 App）
└── lume/    MAGIRRI Lume —— 直出 ARRI 色彩的相机（安卓 App）
```

| | MAGIRRI Vex | MAGIRRI Lume |
|---|---|---|
| 干什么 | 手机拍的 **Magic-Log** 视频 → 还原 → ARRI 色彩 → 导出成片 | 一台**独立相机**，按下快门/录制就直出 ARRI 影调 |
| 输入 | 已有的 Log 视频（先录后修） | 实时取景（边看边拍） |
| 色彩链 | 荣耀还原 LUT → 709→AWG3 → LogC3 → ARRI LUT → 仿阿莱影调 | 恒等（相机已是 Rec.709）→ 同上 |
| 形态 | Python CLI + 安卓 App | 安卓 App |
| 目录 | [`vex/`](vex/README.md) | [`lume/`](lume/README.md) |

两者共用同一条色彩数学（同一套矩阵、同一张 ARRI 官方 LUT、同一条影调曲线），
所以**同一个场景下 Vex 修的片和 Lume 直出的片，色彩口径是一致的**。

## 快速开始

```bash
# Vex：命令行把一条 Log 视频导出成片
cd vex
python -m vex export 你的视频.mp4 -o out.mp4

# Vex：跑全部实验并生成验证报告
python -m vex verify
```

```bash
# 两个 App 的安装包
adb install -r vex/android/dist/magirri-vex-2.0.1-debug.apk
adb install -r lume/dist/magirri-lume-2.0.1-debug.apk
```

## 构建

两个都是 Android 工程，各自独立：

```bash
cd vex/android && ./gradlew assembleDebug    # 或 lume/
```

> 注意：工程路径里有中文时 Gradle 单测会挂，构建建议放到纯 ASCII 路径下
> （例如先 `robocopy` 到 `C:\Android\vex` 再 build）。

## 免责声明 / Disclaimer

- 本项目是**个人自用作品**，与 ARRI 集团（Arnold & Richter Cine Technik GmbH & Co. Betriebs KG）
  **没有任何合作、赞助、授权或从属关系**。文中出现的 ARRI 字样仅用于说明"参考的观感方向"，
  ARRI、ALEXA、LogC 等名称与商标归其权利人所有。
- **Lume 的"电影影调"是自制曲线**，通过矩阵运算与色调曲线近似某种胶片/电影机的观感，
  并非任何厂商的官方 LUT、固件算法或授权色彩科学。它只是"看起来像"，不是"就是"。
- **Vex 的还原 LUT** 需要用户自行从设备厂商处取得（例如手机厂商提供的 Log 还原文件）。
  官方 LUT 的使用范围以其自身授权条款为准，本项目不附带、不再分发任何厂商 LUT。
- 两个 App 均按**"现状"（AS IS）**提供，不提供任何明示或暗示的担保，包括但不限于
  适销性、特定用途适用性与不侵权担保。
- 使用本软件处理素材所产生的任何后果（画面偏差、数据丢失、法律风险等）由使用者**自行承担**。
- 请勿将本软件用于侵犯他人商标权、著作权或其他合法权益的内容制作与传播。
- 如果你所在地区对上述内容的合法性有疑虑，请在使用前咨询专业意见，或不要使用。

> This is a personal project. It is **not affiliated with, endorsed by, or licensed by ARRI**.
> The "cinematic look" is a self-made tone curve loosely inspired by film/cinema camera imagery,
> not an official LUT, firmware, or color science from any manufacturer. All trademarks belong
> to their respective owners. Provided "AS IS", without warranty of any kind.
