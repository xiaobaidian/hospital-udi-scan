# -*- coding: utf-8 -*-
"""UDI 盘点 · Windows 版 入口。

用 pywebview 加载内联 HTML（界面），后端逻辑由 app.Api 暴露给 JS 调用。
运行时依赖系统自带的 Edge WebView2（Win10/11 默认具备），无需打包浏览器内核。
"""
import os
import sys
import traceback
import urllib.request

import webview
from app import Api


def _data_dir():
    # 冻结态：exe 所在目录；开发态：脚本目录
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def html_path():
    """返回 ui/index.html 的绝对路径（开发态与冻结态都适用）。"""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, "ui", "index.html")


def main():
    # 用 file:// 方式加载本地 HTML，比把整页当字符串传给 html= 在 WebView2 下更稳，
    # 可避免大段内联 HTML 传参导致的空白窗口问题。
    p = html_path()
    url = "file://" + urllib.request.pathname2url(os.path.abspath(p))
    api = Api()
    webview.create_window(
        "UDI 盘点",
        url=url,
        js_api=api,
        width=1000,
        height=720,
        min_size=(860, 560),
    )
    webview.start(debug=False)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # 窗口程序无控制台，异常写入日志便于排查
        log_path = os.path.join(_data_dir(), "udi_error.log")
        try:
            with open(log_path, "a", encoding="utf-8") as f:
                f.write("=== %s ===\n" % sys.executable)
                f.write(traceback.format_exc())
                f.write("\n")
        except Exception:
            pass
        raise

