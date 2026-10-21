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
adb install -r vex/android/dist/magirri-vex-1.0-debug.apk
adb install -r lume/dist/magirri-lume-1.0-debug.apk
```

## 构建

两个都是 Android 工程，各自独立：

```bash
cd vex/android && ./gradlew assembleDebug    # 或 lume/
```

> 注意：工程路径里有中文时 Gradle 单测会挂，构建建议放到纯 ASCII 路径下
> （例如先 `robocopy` 到 `C:\Android\vex` 再 build）。

## 品牌

- **MAGIRRI**：母品牌
- **Vex**：把 Log 素材"捞回来"（vex 有"使困扰、折腾"的意思，对应 Log 素材的麻烦）
- **Lume**：相机（lume 是"光"的词根）

更名前的内部代号：Vex 曾叫 `magicrri` / `Magirri`，Lume 曾叫 `ArcCam` / `arcam`。
