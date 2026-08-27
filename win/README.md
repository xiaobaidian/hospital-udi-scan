# UDI 盘点 · Windows 版

医疗器械 UDI 扫码盘点工具的 Windows 桌面版。去掉了安卓版的摄像头扫码，改用**扫码枪 / 手动粘贴 / 批量录入**，其余功能全部保留：

- GS1 条码解析（UDI / 批号 / 效期 / 生产 / 序列号；含 30 开头序列号、11/17 日期校验收紧 25–35）
- NMPA 数据库直连查询（零依赖，按 14 位 UDI 反查产品名 / 规格 / 厂家，三态：已查 / 待核对 / 无记录）
- 本地 SQLite 缓存（查过的 UDI 落库，下次免联网；离线可用）
- 离线字典库（手动添加 / 导入 JSON / 导出，优先级高于官方缓存）
- 盘点清单（增删、数量步进、状态标记、持久化）
- 导出 JSON / CSV 到 exe 同目录

## 技术栈
- Python 3.13 + **pywebview**（界面为内联 HTML/CSS/JS，复用移动版设计）
- 后端零依赖：GS1 解析 / NMPA 查询（urllib）/ 存储（sqlite3）均为标准库
- 运行时调用系统自带的 **Edge WebView2**，不打包浏览器内核，单 exe 体积小
- 打包：PyInstaller `--onefile --windowed`，产出一个可携 `UDI盘点.exe`

## 目录结构
```
hospital-udi-scan-win/
├─ main.py          # 入口：加载 UI + 启动 webview
├─ app.py           # JS<->Python 桥接 API
├─ gs1_parser.py    # GS1 解析（移植自安卓版）
├─ nmpa_client.py   # NMPA 直连查询
├─ db_store.py      # SQLite：缓存 + 字典 + 清单
├─ ui/index.html    # 界面（HTML/CSS/JS）
├─ build.bat        # PyInstaller 打包脚本
└─ udi_cache.db     # 运行时自动生成（数据，与 exe 同目录）
```

## 运行（开发）
```bash
pip install pywebview
python main.py
```

## 打包为便携 exe
```bash
build.bat
# 或
python -m PyInstaller --noconfirm --onefile --windowed --name "UDI盘点" --add-data "ui/index.html;ui" main.py
```
产物：`dist\UDI盘点.exe`。把它和 `udi_cache.db`（首次运行自动生成）放在任意文件夹 / U 盘即可使用。

## 数据位置
数据库 `udi_cache.db` 位于 exe 同目录，便于随 exe 整体迁移。清单、缓存、字典均存于此。

## 故障排查
窗口程序无控制台，若启动异常会在 exe 同目录写入 `udi_error.log`（含完整堆栈）。把它发给我即可定位。
注意：请始终把 `UDI盘点.exe` 放在**有写入权限**的目录（如 D 盘文件夹 / U 盘），不要放在 `C:\Program Files` 等受保护目录，否则无法生成数据库。

## 在 WorkBuddy 隔离 Python 下重新打包
本机打包需用已装好 `pywebview` 与 `pyinstaller` 的解释器：
```bash
"C:/Users/xiaob/.workbuddy/binaries/python/envs/default/Scripts/python.exe" -m PyInstaller --noconfirm --onefile --windowed --name "UDI盘点" --add-data "ui/index.html;ui" main.py
```
产物：`dist\UDI盘点.exe`。

## 录入方式
- **单条（扫码）**：聚焦录入框后扫码枪直接扫 → 自动解析预览（**不入库**）；
  确认无误后按**键盘 Enter** 才加入清单。程序按按键节奏区分「扫码枪回车」与「键盘回车」
  （扫码枪逐字符极快、回车紧随，仅预览；键盘回车间隔明显，才入库），可连续扫描。
- 手动：粘贴 / 输入条码，按键盘 Enter 加入（同样会先校验完整性）。
- **批量**：切到「批量」模式，粘贴多行条码，点「**解析**」按钮逐条校验
  （批量不实时解析，因为扫码节奏不可控）。解析失败的行会标红并注明原因
  （如「缺 效期」「UDI 不在开头」），点「将成功项加入清单」只入库成功行。

## 入库校验规则
一条条码要能入库，必须同时满足：
1. **UDI 在开头**（第一条解析字段为 UDI / AI `01`）；
2. 至少包含 **UDI、效期（AI `17`）、批号（AI `10`）** 三类。

缺任一项或 UDI 不在开头，单条按 Enter 不会入库（预览区给出警告），批量则标为失败行。
