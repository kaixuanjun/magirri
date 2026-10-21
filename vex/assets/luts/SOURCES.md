# LUT 资产来源

本目录的两个子目录是**厂商官方发布**的 LUT，由 `python -m magicrri assets fetch` 抓取。
它们不是自由软件，**不要随代码一起再分发**（`.gitignore` 已排除）。

## honor/ —— 荣耀 Magic-Log → Rec.709 还原 LUT

| 文件 | 版本 | 适用机型 |
|---|---|---|
| `honor_logv1_rec709_v3_33.cube` | Magic-Log v1 | Magic3 系列 / Magic V / Magic4 / Magic5 / Magic Vs / Magic V2 |
| `honor_new_logv2_rec709_v9.cube` | Magic-Log v2 | Magic4 Pro+ / Magic5 Pro+ / Magic6–Magic8 / Magic V3 / V5 |

- 来源页：<https://www.honor.com/cn/phones/rec-lut-download/>
- 直链：`https://www-file.honor.com/content/dam/honor/cn/rec-lut-download/img/<文件名>`
- 抓取日期：2026-10
- 规格：`LUT_3D_SIZE 33`，`DOMAIN_MIN 0 0 0`（文件未写，按默认），**R 变化最快**
- 说明：荣耀官方只写"用于在剪辑软件中还原"，未说明输出是 BT.709 OETF 还是显示 gamma。
  本工具默认按显示 gamma 2.4 解释（`--mid-transfer bt1886`），可切换。

## arri/ —— ARRI LogC3 → Rec.709 LUT 包 v1-2-1

- 来源页：<https://www.arri.com/en/learn/camera-systems/tools/lut-generator>
- 包名：`ARRI - LogC3 LUT Package_data.zip`（约 46MB，含 48 个 .cube + 命名规范 PDF）
- 抓取日期：2026-10
- 本工具用到的三个：

| 文件 | 用途 |
|---|---|
| `ARRI_LogC3-to-Gamma24_Rec709_D65-v1_33.cube` | 默认（`--look standard`） |
| `ARRI_LogC3-to-Gamma24_Rec709_D65-Classic_33.cube` | `--look classic` |
| `ARRI_LogC3-to-Gamma24_Rec2020_D65-v1_33.cube` | `--look rec2020`（Rec.2020 基色） |

- 关键事实：文件名里的 **Gamma24** 表示输出是 **gamma 2.4 显示码值**，
  不是 BT.709 OETF。所以本工具默认 `--output-transfer keep`，原样写进 bt709 标记的文件——
  现代播放器用 BT.1886（≈2.4）EOTF 还原，正好对上。
  若你的交付要求严格 OETF，用 `--output-transfer bt709`。

## 如果链接失效

`python -m magicrri assets fetch --force` 会重下。ARRI 的直链是 canto CDN 的临时地址，
失效时去上面的来源页点一次下载按钮即可（页面里有同样文件）。荣耀的直链是静态 CDN，
一般长期有效。
