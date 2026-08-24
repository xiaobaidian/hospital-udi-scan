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
| 扫码方案 | `zxing-android-embedded:4.3.0`（**AndroidX 版、不依赖 GMS**，普通机可用；2.3.0 依赖旧 Support Library 与 AGP 8 冲突）→ 已验证一次构建过 |
| GS1 解析 | **自写 `Gs1Parser` v2：前缀扫描 + 位置切分引擎**。统一覆盖括号 HRI / FNC1 分隔 / **纯前缀拼接（无括号、FNC1 被剥离，`01`+14位UDI 直接拼接）** / 裸 UDI 四种输入。补齐 `(91)` 序列号；定长 AI 边界需满足后续长度防误切 |
| NMPA 查询 | **自写 `NmpaClient`**：`HttpURLConnection` 直连后端，零依赖 |
| **本地缓存 + 覆盖字典** | **`NmpaCache`（SQLite）**：联网查到的 NMPA 结果落 `udi_cache` 表（不过期）；用户改名/查不到时手补的落 `udi_override` 表。**读取优先级 override > cache**，用户修正永远生效。「字典」入口可导出/导入 override JSON 做多设备同步 |
| 导出格式 | **JSON + CSV 都给**（默认双出口），经系统分享面板（微信/留存） |
| 与桌面衔接 | 导出 JSON/CSV → 桌面 `hospital-inventory-pipeline` 读取合并 |
| 离线补查 / 本地字典 | 缓存命中直接复用；override 命中标 `local`；NMPA 查不到可手动补「本地字典」条目，仍可被搜索/导出 |
| 多设备同步 | **文件级 JSON 导入导出**（零服务器）：导出 `udi_overrides.json` 经微信/网盘传另一台「导入」即可；导入用事务批量写，不卡扫码 |

## 2. "两行条码" + 扫描字符串格式（用户最关心的点）

### 2.1 扫描字符串的真实格式（实测关键）
- OCR 看到条码下方印 `(01)(17)(10)`，但**扫码拿到的字符串是 `01` + 14位UDI 这种无括号、靠 AI 前缀拼接、`FNC1` 被剥离**的格式（多机型 zxing 把 FNC1 剥离后串退化成裸前缀拼接）。
- 因此解析引擎**不能依赖括号或 FNC1 分隔符**，必须以「前缀扫描 + 位置切分」为核心。

### 2.2 解析引擎 v2（前缀扫描 + 位置切分）
1. 归一化：剥括号；FNC1(ASCII 29) **直接删除**（不留空格，避免定长值误吞分隔符）。
2. 若整串「不以已知 AI 开头」（裸日期 `250631`、裸非 GTIN 14 位等）→ 走 `fallbackHeuristic` 兜底，不进 AI 扫描（避免纯数字串被切碎）。
3. 主路径 `scanByAiPrefix`：扫整串所有 AI 前缀位置，按定长/变长切值。
   - **AI 集合精简**为 `01/02/11/17`(定长) + `10/21/91`(变长)，移除 `250/12/13/15/16/20/...` 等易与裸数字串冲突的 AI。
   - **变长值边界** = 下一个真 AI 起点；**定长 AI 边界需满足后续长度**（防 `2024` 里的 `02` 误判）。
4. 兜底 `fallbackHeuristic`：GTIN-14/13/12/8 校验位判定 → UDI；6 位像日期 → 效期；歧义段 → UNKNOWN 等用户手动指定。
- 验证：`tools/verify_parser.py`（Python 复刻算法，覆盖真实样例 12/12 通过）。

### 2.3 两行条码合并
- 方案：**连续扫码 + 同缓冲去重 + 按 AI 键并集合并**。
  1. `scannedRaws`（本次缓冲内原始串集合）去重；另加 **800ms 时间窗去抖**（`lastRawHandled`）防同帧狂触发。
  2. 每次用 `Gs1Parser` 解析成字段，按 AI 键合并进缓冲：该 AI 已有值则保留、未设则填入。「行1 扫 (01)UDI」「行2 扫 (10)批号 (17)效期」自然并入同一条。
  3. 出现 UDI 即自动触发 NMPA 查询，查回名称回填。
  4. **UDI 冲突**：已缓冲 UDI 又扫到不同 UDI → 自动提交当前缓冲并开新条目 + 黄条提示。
  5. 用户点「加入清单」提交为 `ScanItem` 并清空。

## 3. 技术栈与构建
- Kotlin 2.0.21 + AGP 8.13.0 + Java 17 + compileSdk/targetSdk 36。
- 依赖：`androidx.core/core-ktx`、`appcompat`、`material:1.12.0`、`recyclerview`、`cardview`、`zxing-android-embedded:4.3.0`。
- 出包：**本机/沙箱无法构建 APK**（到 `dl.google.com` 限速、制品主机被墙；且 git 智能 HTTP 协议流被沙箱截断，无法 `git push`），沿用已验证的 **GitHub Actions 云构建**：沙箱通过 **GitHub REST API（Git Data/Contents/Commit comments）** 推送源码 → 触发 CI → `ncipollo/release-action` 发 Release 上传 APK，标签 `debug-latest` 覆盖更新，得匿名公开直链。
- 固定 debug 签名（复用同一把密钥），保证可覆盖安装。
- 构建坑位已排雷：`viewBinding` 必须关掉改用 `findViewById`（否则 binding 类不生成导致编译失败）；`zxing-android-embedded` 必须用 AndroidX 版 4.x（2.3.0 的旧 Support Library 与 AGP 8 冲突）。

## 4. 目录结构
```
app/src/main/java/com/hospital/udiscan/
  MainActivity.kt   扫码/合并/自动查名/清单/导出 主界面（缓存优先）
  Gs1Parser.kt      GS1 AI 解析（含 (91)）
  NmpaClient.kt     NMPA 正向查询（三态）
  NmpaCache.kt      SQLite 本地缓存 + 覆盖字典（udi_cache / udi_override 两表，override 优先；导入导出 JSON 事务批量写）
  ScanItem.kt       一条记录数据类
  ItemAdapter.kt    清单 RecyclerView 适配器（含「编辑」「删除」）
```

## 5. 后续可扩展（v2+）
- 桌面管线可直接读取 override：`hospital-inventory-pipeline` 侧若需复用字典，可让 App 导出的 override JSON 一并喂入。
- 多盒/多货位分组、扫码绑定货位（取景框实时叠加）。
- 桌面管线的"UDI 反向查型号"能力若需移动端也可加。
- 缓存「清空/刷新」UI 入口（目前 `NmpaCache.clearAll()`/`clearOverrides()` 已就绪，「字典」弹窗可加清空按钮）。
