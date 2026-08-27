@echo off
rem 用 PyInstaller 打包成单文件便携 exe（零外部依赖，运行时调用系统 WebView2）。
rem 输出：dist\UDI盘点.exe
setlocal
set PY=python
%PY% -m PyInstaller --noconfirm --onefile --windowed --name "UDI盘点" --add-data "ui/index.html;ui" main.py
echo.
echo 打包完成，exe 位于 dist\UDI盘点.exe
pause
