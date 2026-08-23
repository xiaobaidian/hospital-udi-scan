# UDI 扫码盘点 App — 设计与开发文档

> 目标：手机扫医疗器械条码（UDI），自动解析 GS1 字段（批号/效期/生产/序列号），正向查 NMPA 拿到产品名称/规格/厂家，录入清单并导出，喂给桌面盘点管线。

## 0. 一句话流程
扫码（多码同框 + 跨帧累积合并）→ GS1 解析（按 AI 键并集合并，重点处理"UDI 与后续条码分两行"）→ UDI 正向查 NMPA（`searchType=1`+纯UDI，ok/pending/skip 三态）→ 数量步进 → 加入本地清单 → 导出 JSON/CSV 喂桌面 `hospital-inventory-pipeline`。

## 1. 已确认决策（2026-08-23）
| 项 | 决策 |
|---|---|
| 工程独立性 | **独立新工程**，不与拍照 App（`hospital-native-apk`/`com.hospital.photolog`）混合 |
| 仓库 / 包名 | `xiaobaidian/hospital-udi-scan` / `com.hospital.udiscan` |
| minSdk | **24**（Android 7.0+，覆盖绝大多数在用机） |
| 设备适配 | 用户明确**无华为适配需求，正常 Android 机可用即可** |
| 扫码方案 | `zxing-android-embedded:2.3.0`（**不依赖 GMS**，普通机可用；因无华为诉求未引入 ML Kit） |
| GS1 解析 | **自写 `Gs1Parser`**：参考 `Keaze/GS1ParserKt` 思路，但**补齐 (91) 序列号**，支持 HRI 括号化与 FNC1 分隔两种输入 |
| NMPA 查询 | **自写 `NmpaClient`**：`HttpURLConnection` 直连后端，零依赖 |
| 导出格式 | **JSON + CSV 都给**（默认双出口），经系统分享面板（微信/留存） |
| 与桌面衔接 | 导出 JSON/CSV → 桌面 `hospital-inventory-pipeline` 读取合并 |
| 离线补查 | v1 在网络可用时查；无网/失败标 `err`，可用「查NMPA」按钮稍后重查 |

## 2. "两行条码"如何处理（用户最关心的点）
- 现象：标签上 UDI 码和批号/效期码分成两行，或一图多码。
- 方案：**连续扫码 + 同缓冲去重 + 按 AI 键并集合并**。
  1. `scannedRaws`（本次缓冲内的原始串集合）做去重，避免同一帧被连续重读反复添加。
  2. 每次识别到的条码用 `Gs1Parser` 解析成 AI→值 的字段，按 AI 键**合并进当前缓冲**：该 AI 已有值则保留、未设则填入。于是"行1 扫到 (01)UDI"、"行2 扫到 (10)批号 (17)效期"自然并入同一条待录入。
  3. 出现 UDI 即**自动触发 NMPA 查询**，查回名称回填。
  4. 用户点「加入清单」把缓冲提交为一条 `ScanItem` 并清空，重新开始下一盒/下一物。
- 变长 AI 在没有 FNC1 分隔符时存在误切风险；实测 GS1-128 经 zxing 会保留 FNC1(ASCII 29)，故可靠。纯文本/非 GS1 码（如纯序列号）在缓冲为空时暂存为 (91)，否则忽略。

## 3. 技术栈与构建
- Kotlin 2.0.21 + AGP 8.13.0 + Java 17 + compileSdk/targetSdk 36。
- 依赖：`androidx.core/core-ktx`、`appcompat`、`material:1.12.0`、`recyclerview`、`cardview`、`zxing-android-embedded:2.3.0`。
- 出包：**本机/沙箱无法构建 APK**（到 `dl.google.com` 限速、制品主机被墙），沿用已验证的 **GitHub Actions 云构建** → `ncipollo/release-action` 发 Release 上传 APK，标签 `debug-latest` 覆盖更新，得匿名公开直链。
- 固定 debug 签名（复用同一把密钥），保证可覆盖安装。

## 4. 目录结构
```
app/src/main/java/com/hospital/udiscan/
  MainActivity.kt   扫码/合并/自动查名/清单/导出 主界面
  Gs1Parser.kt      GS1 AI 解析（含 (91)）
  NmpaClient.kt     NMPA 正向查询（三态）
  ScanItem.kt       一条记录数据类
  ItemAdapter.kt    清单 RecyclerView 适配器
```

## 5. 后续可扩展（v2+）
- 本地命中缓存（同一 UDI 不重复联网）。
- 离线导出后由桌面管线批量补查（已有 `hospital-inventory-pipeline`）。
- 多盒/多货位分组、扫码绑定货位（取景框实时叠加）。
- 桌面管线的"UDI 反向查型号"能力若需移动端也可加。
