# -*- coding: utf-8 -*-
"""GS1 应用标识符解析（移植自安卓版 Gs1Parser.kt，逻辑经等价测试验证）。

支持四种输入一份代码覆盖：
  1) 括号 HRI：        (01)0694...(17)250631(10)LOT123
  2) FNC1 分隔：       01 0694... <GS> 17 250631 <GS> 10 LOT123
  3) 纯前缀拼接(无分隔)：01 0694... 10 LOT123 17 250631   ← 最常见
  4) 裸串(单码无 AI)：  06949450446782 / LOT123 / 250631

修复要点（与安卓版一致）：
  - 序列号(21/91)按 GS1 惯例位于串尾，其值是任意数字组合（如 30 开头的长串
    内部极易撞上 01/10/11/17/21 等伪前缀），故序列号字段不做 AI 边界扫描，
    只按空格（两行条码换行处）断，其余一律吃到串尾。
  - 11/17 需先取 6 位数字判断是否为真日期；年份限定 25..35（2025-2035）。
  - AI 边界判定处对 11/17 增加"值必须像真日期"校验，防伪边界切碎变长字段。
"""
import re

FIXED = {"01": 14, "02": 14, "11": 6, "17": 6}
VAR = {"10", "21", "91"}
ALL_AI = set(FIXED.keys()) | VAR

FIELD_TYPES = {
    "01": "UDI", "02": "UDI", "10": "BATCH", "17": "EXPIRY",
    "11": "PROD_DATE", "21": "SERIAL", "91": "SERIAL",
}
FIELD_LABELS = {
    "UDI": "UDI", "BATCH": "批号", "EXPIRY": "效期", "PROD_DATE": "生产",
    "SERIAL": "序列号", "UNKNOWN": "未归类",
}


def _type_of(ai):
    return FIELD_TYPES.get(ai, "UNKNOWN")


def looks_like_date(yymmdd):
    if len(yymmdd) != 6 or not yymmdd.isdigit():
        return False
    yy = int(yymmdd[:2]); mm = int(yymmdd[2:4]); dd = int(yymmdd[4:6])
    return 25 <= yy <= 35 and 1 <= mm <= 12 and 1 <= dd <= 31


def _evaluate(fields):
    """判定一条解析结果是否满足入库要求：
    - UDI 必须位于最前（fields[0] 为 UDI）
    - 至少包含 UDI、效期(17)、批号(10) 三类
    返回 {complete, missing, order_ok, error}。
    """
    types = {f["type"] for f in fields}
    need = [("UDI", "UDI"), ("EXPIRY", "效期"), ("BATCH", "批号")]
    missing = [label for key, label in need if key not in types]
    order_ok = bool(fields) and fields[0]["type"] == "UDI"
    complete = (not missing) and order_ok
    if missing:
        err = "缺 " + "/".join(missing)
    elif not order_ok:
        err = "UDI 不在开头"
    else:
        err = ""
    return {"complete": complete, "missing": missing, "order_ok": order_ok, "error": err}


def _is_ai_boundary(s, pos):
    if pos + 3 <= len(s):
        a3 = s[pos:pos + 3]
        if a3.isdigit() and a3 in ALL_AI:
            if a3 in FIXED:
                return pos + 3 + FIXED[a3] <= len(s)
            return True
    if pos + 2 <= len(s):
        a2 = s[pos:pos + 2]
        if a2.isdigit() and a2 in ALL_AI:
            if a2 in FIXED:
                if pos + 2 + FIXED[a2] > len(s):
                    return False
                if a2 in ("11", "17") and not looks_like_date(s[pos + 2:pos + 2 + FIXED[a2]]):
                    return False
                return True
            return True
    return False


def _ai_at(s, pos):
    if pos + 3 <= len(s):
        a3 = s[pos:pos + 3]
        if a3.isdigit() and a3 in ALL_AI:
            if a3 in FIXED and pos + 3 + FIXED[a3] > len(s):
                return None
            return a3
    if pos + 2 <= len(s):
        a2 = s[pos:pos + 2]
        if a2.isdigit() and a2 in ALL_AI:
            if a2 in FIXED and pos + 2 + FIXED[a2] > len(s):
                return None
            return a2
    return None


def _starts_with_ai(s):
    if len(s) >= 3 and s[:3].isdigit() and s[:3] in ALL_AI:
        return True
    if len(s) >= 2 and s[:2].isdigit() and s[:2] in ALL_AI:
        return True
    return False


def _scan_by_ai_prefix(clean):
    s = clean
    n = len(s)
    fields = []
    i = 0
    while i < n:
        if s[i] == ' ':
            i += 1
            continue
        ai = _ai_at(s, i)
        if ai is None:
            if not fields:
                return []
            break
        i += len(ai)
        if ai in FIXED:
            ln = FIXED[ai]
            value = s[i:i + ln].strip()
            i += ln
            if ai in ("11", "17") and not looks_like_date(value):
                fields.append(_field("UNKNOWN", ai, value, "UNCERTAIN"))
                continue
        else:
            serial_tail = ai in ("21", "91")
            start = i
            while i < n:
                if s[i] == ' ':
                    break
                if not serial_tail and _is_ai_boundary(s, i):
                    break
                i += 1
            value = s[start:i]
        if value:
            fields.append(_field(_type_of(ai), ai, value, "AI_PREFIX"))
    return fields


def _field(ftype, ai, value, source):
    return {"type": ftype, "ai": ai, "value": value, "source": source,
            "label": FIELD_LABELS.get(ftype, "未归类")}


def _fallback(digits, already_has_udi):
    if digits.startswith("21") or digits.startswith("91"):
        rest = digits[2:]
        if rest:
            return _field("SERIAL", digits[:2], rest, "AI_PREFIX")
    if digits.startswith("11") or digits.startswith("17"):
        rest = digits[2:]
        if len(rest) == 6 and looks_like_date(rest):
            return _field(_type_of(digits[:2]), digits[:2], rest, "AI_PREFIX")
        return _field("UNKNOWN", digits[:2], rest, "UNCERTAIN")

    def udi_field(g14):
        return _field("UDI", "01", g14, "AI_PREFIX")

    def batch_field(v):
        return _field("BATCH", "10", v, "HEURISTIC")

    def serial_field():
        return _field("SERIAL", "21", digits, "HEURISTIC")

    if len(digits) == 14:
        if _is_valid_gtin14(digits):
            return udi_field(digits)
        return batch_field(digits) if already_has_udi else _field("UNKNOWN", None, digits, "UNCERTAIN")
    if len(digits) == 13:
        g14 = "0" + digits
        if _is_valid_gtin14(g14):
            return udi_field(g14)
        return batch_field(g14) if already_has_udi else _field("UNKNOWN", None, digits, "UNCERTAIN")
    if len(digits) == 12 and digits.isdigit():
        g14 = "00" + digits
        if _is_valid_gtin14(g14):
            return udi_field(g14)
        return batch_field(digits) if already_has_udi else _field("UNKNOWN", None, digits, "UNCERTAIN")
    if len(digits) == 8 and digits.isdigit():
        g14 = "000000" + digits
        if _is_valid_gtin14(g14):
            return udi_field(g14)
        return batch_field(digits) if already_has_udi else _field("UNKNOWN", None, digits, "UNCERTAIN")
    if len(digits) == 6 and looks_like_date(digits):
        return _field("EXPIRY", "17", digits, "HEURISTIC")
    if any(c.isalpha() for c in digits) and 3 <= len(digits) <= 30:
        return serial_field() if already_has_udi else _field("UNKNOWN", None, digits, "UNCERTAIN")
    if 7 <= len(digits) <= 20:
        return serial_field() if already_has_udi else _field("UNKNOWN", None, digits, "UNCERTAIN")
    return _field("UNKNOWN", None, digits, "UNCERTAIN")


def _is_valid_gtin14(gtin):
    if len(gtin) != 14 or not gtin.isdigit():
        return False
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(gtin[:13]))
    return (10 - (total % 10)) % 10 == int(gtin[-1])


def parse(raw, already_has_udi=False):
    """解析入口。返回 {"fields":[{type,ai,value,source,label}], "raw":raw,
    "complete":bool, "missing":[...], "order_ok":bool, "error":str}。"""
    if not raw:
        return {"fields": [], "raw": raw, "complete": False,
                "missing": ["UDI", "效期", "批号"], "order_ok": False, "error": "空"}
    fnc1 = chr(29)
    clean = (raw.replace("(", "").replace(")", "").replace(fnc1, "")
             .replace("\n", " ").replace("\r", " "))
    stripped = clean.replace(" ", "")
    fields = []
    if stripped and _starts_with_ai(stripped):
        fs = _scan_by_ai_prefix(clean)
        if fs:
            fields = fs
    if not fields:
        fb = _fallback(stripped, already_has_udi)
        if fb is not None:
            fields = [fb]
    if not fields:
        fields = [_field("UNKNOWN", None, clean.strip(), "UNCERTAIN")]
    res = {"fields": fields, "raw": raw}
    res.update(_evaluate(fields))
    return res


def format_date_yymmdd(yymmdd):
    if not yymmdd or len(yymmdd) != 6:
        return yymmdd
    try:
        from datetime import datetime
        d = datetime.strptime(yymmdd, "%y%m%d")
        return d.strftime("%Y-%m-%d")
    except Exception:
        return yymmdd


if __name__ == "__main__":
    import json
    tests = [
        "01069494504467821726123110LOT12321301125012345",
        "(01)06949450446782(17)260631(10)LOT123(21)30ABC456",
        "010694945044678217261231 2130AB9988",
    ]
    for t in tests:
        print(json.dumps(parse(t), ensure_ascii=False))
