# -*- coding: utf-8 -*-
"""直连 NMPA UDI 数据库后端接口（零依赖，仅标准库）。

正确语法：searchType=1 + query=纯14位UDI（跨字段模糊匹配）。
primaryDeviceId 只是返回字段，不能作搜索条件（会 500）。
返回三态：ok（primaryDeviceId 精确等于 UDI）/ pending（命中但需人工核对）/
          skip（无记录）/ err（网络失败）。

移植自安卓版 NmpaClient.kt。
"""
import json
import time
import ssl
import urllib.request
import urllib.parse

ENDPOINT = "https://udi.nmpa.gov.cn/getDeviceList.html"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def query(udi, timeout=20):
    """返回 dict: {state, productName, specification, companyName}。"""
    pure = (udi or "").strip()
    if not pure:
        return {"state": "skip", "productName": None, "specification": None, "companyName": None}
    body = (
        "query=" + urllib.parse.quote(pure, safe="")
        + "&searchType=1"
        + "&_search=false"
        + "&nd=" + str(int(time.time() * 1000))
        + "&rows=20&page=1&sidx=&sord=asc"
    )
    req = urllib.request.Request(ENDPOINT, data=body.encode("utf-8"), method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    req.add_header("Referer", "https://udi.nmpa.gov.cn/")
    req.add_header("X-Requested-With", "XMLHttpRequest")
    req.add_header("Accept", "application/json, text/javascript, */*; q=0.01")
    req.add_header("User-Agent", UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as resp:
            if resp.status != 200:
                return {"state": "err", "productName": None, "specification": None, "companyName": None}
            text = resp.read().decode("utf-8")
        return _parse(text, pure)
    except Exception:
        return {"state": "err", "productName": None, "specification": None, "companyName": None}


def _parse(text, udi):
    try:
        root = json.loads(text)
    except Exception:
        return {"state": "err", "productName": None, "specification": None, "companyName": None}
    rows = root.get("rows") or []
    if not rows:
        return {"state": "skip", "productName": None, "specification": None, "companyName": None}
    for row in rows:
        if (row.get("primaryDeviceId") or "") == udi:
            return _hit("ok", row)
    return _hit("pending", rows[0])


def _hit(state, row):
    return {
        "state": state,
        "productName": (row.get("productName") or "") or None,
        "specification": (row.get("specification") or "") or None,
        "companyName": (row.get("companyName") or "") or None,
    }


if __name__ == "__main__":
    import sys
    u = sys.argv[1] if len(sys.argv) > 1 else "06949450446782"
    print(json.dumps(query(u), ensure_ascii=False, indent=2))
