# UDI 扫码盘点 App（hospital-udi-scan）

手机一扫医疗器械条码，自动解析 GS1 字段（UDI/批号/效期/生产/序列号），正向查 NMPA 拿产品名称/规格/厂家，录入清单并导出 JSON/CSV，喂给桌面盘点管线。

## 功能
- 连续扫码，支持**一图多码 / 多帧累积合并**（解决"UDI 与批号效期分两行"）。
- 自写 **GS1 解析器**：支持 `(01)UDI-DI` `(10)批号` `(17)效期` `(11)生产` `(21)/(91)序列号`，含 (91) 自定义序列号。
- **NMPA 正向查询**（直连后端，零依赖）：`searchType=1` + 纯 14 位 UDI，返回 已查 / 待核对 / 无记录 三态。
- 数量步进器，本地清单（可删除/清空）。
- 导出 **JSON + CSV**，经系统分享面板发微信或留存。
- **本地 SQLite 缓存**：查过的 UDI 落库，下次同款直接读库不联网；重复扫码不再反复请求，无网也能复用已查结果。

## 下载
最新 debug 版（公开直链，覆盖更新）：
https://github.com/xiaobaidian/hospital-udi-scan/releases/download/debug-latest/app-debug.apk

> 包名 `com.hospital.udiscan`。首次安装需允许"未知来源"。首次查某 UDI 需联网 NMPA，之后同 UDI 走本地缓存无需联网。

## 用法
1. 打开 App，授予相机权限。
2. 将条码放入取景框；同一物品的多个条码（分两行也可）会并入"当前待录入"。
3. 出现 UDI 会自动查 NMPA 并回填名称。
4. 调数量 →「加入清单」。
5. 顶部「导出 JSON / CSV」选择微信或文件管理器发送。

## 工程
- Kotlin + AGP 8.x + Java 17，minSdk 24。
- 扫码：`zxing-android-embedded:4.3.0`（AndroidX 版，不依赖 GMS）。
- 详见 [DESIGN.md](DESIGN.md)。
- 与桌面管线 `hospital-inventory-pipeline` 衔接：导出的 JSON/CSV 可喂其合并汇总。
