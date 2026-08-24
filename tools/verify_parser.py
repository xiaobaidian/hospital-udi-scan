#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gs1Parser 解析引擎的本地算法验证（参考实现，与 Gs1Parser.kt 逻辑对齐）。
沙箱无法跑 Gradle，故用纯 Python 复刻核心算法，对你的真实样例做正确性验证。
Kotlin 端另附 JUnit 单测随 CI 回归。
"""
import re

# 只保留医疗器械盘点场景真正关心、且不与他人裸数字串冲突的核心 AI。
# 移除 250/12/13/15/16/20/240/241/242/400/401/422/710/8004：它们在真实场景极少用，
# 却会跟裸 6 位日期(250631)、裸 12/8 位 UPC 冲突，导致误切。
FIXED = {"01": 14, "02": 14, "11": 6, "17": 6}
VAR = {"10", "21", "91"}
ALL_AI = set(FIXED.keys()) | VAR

TYPE_MAP = {"01": "UDI", "02": "UDI", "10": "BATCH", "17": "EXPIRY",
            "11": "PROD_DATE", "21": "SERIAL", "91": "SERIAL"}


def is_valid_gtin14(gtin: str) -> bool:
    if len(gtin) != 14 or not gtin.isdigit():
        return False
    s = sum((d * 3 if i % 2 == 0 else d) for i, d in enumerate(map(int, gtin[:13])))
    return (10 - (s % 10)) % 10 == int(gtin[-1])


def looks_like_date(d: str) -> bool:
    if len(d) != 6 or not d.isdigit():
        return False
    yy, mm, dd = int(d[0:2]), int(d[2:4]), int(d[4:6])
    return 0 <= yy <= 99 and 1 <= mm <= 12 and 1 <= dd <= 31


def is_ai_at(s: str, pos: int, length: int) -> bool:
    if pos + length > len(s):
        return False
    sub = s[pos:pos + length]
    return sub.isdigit() and sub in ALL_AI


def is_ai_boundary(s: str, pos: int) -> bool:
    """判断位置 pos 是否是「真 AI 起点」：不仅是数字串在 ALL_AI，
    定长 AI 还要求其后跟的字符数达到定长（避免 2024 里的 02 被误判为 AI）。"""
    if pos + 3 <= len(s) and s[pos:pos + 3].isdigit() and s[pos:pos + 3] in ALL_AI:
        ai = s[pos:pos + 3]
        if ai in FIXED:
            return pos + 3 + FIXED[ai] <= len(s)  # 后续长度足够承载定长值
        return True
    if pos + 2 <= len(s) and s[pos:pos + 2].isdigit() and s[pos:pos + 2] in ALL_AI:
        ai = s[pos:pos + 2]
        if ai in FIXED:
            return pos + 2 + FIXED[ai] <= len(s)
        return True
    return False


def ai_at(s: str, pos: int):
    """返回 pos 处的 AI（3 位优先，再 2 位），非真边界返回 None。"""
    if pos + 3 <= len(s) and s[pos:pos + 3].isdigit() and s[pos:pos + 3] in ALL_AI:
        ai = s[pos:pos + 3]
        if ai in FIXED and pos + 3 + FIXED[ai] > len(s):
            pass
        else:
            return ai
    if pos + 2 <= len(s) and s[pos:pos + 2].isdigit() and s[pos:pos + 2] in ALL_AI:
        ai = s[pos:pos + 2]
        if ai in FIXED and pos + 2 + FIXED[ai] > len(s):
            pass
        else:
            return ai
    return None


def scan_by_ai_prefix(clean: str):
    s = clean
    n = len(s)
    fields = []
    i = 0
    while i < n:
        if s[i] == ' ' or s[i].isspace():
            i += 1
            continue
        ai = ai_at(s, i)
        if ai is None:
            if not fields:
                return []
            break
        i += len(ai)
        if ai in FIXED:
            length = FIXED[ai]
            value = s[i:i + length].strip()
            i += length
            # 日期型定长 AI(11/17)：后面必须是 6 位真日期才算数
            if ai in ("11", "17") and not looks_like_date(value):
                fields.append(("UNKNOWN", ai, value))
                continue
        else:
            # 变长值：吃到「下一个真 AI 边界」或分隔符或串尾
            start = i
            while i < n:
                if s[i] == ' ':
                    break
                if is_ai_boundary(s, i):
                    break
                i += 1
            value = s[start:i]
        if not value:
            continue
        fields.append((TYPE_MAP.get(ai, "UNKNOWN"), ai, value))
    return fields


def _starts_with_ai(s: str) -> bool:
    if not s:
        return False
    # 串首是已知 AI（3 位优先，再 2 位）
    if len(s) >= 3 and s[0:3].isdigit() and s[0:3] in ALL_AI:
        return True
    if len(s) >= 2 and s[0:2].isdigit() and s[0:2] in ALL_AI:
        return True
    return False


def fallback_heuristic(digits: str, already_has_udi: bool):
    # 裸串以序列号 AI 开头（21/91）：后面整段即序列号
    if digits.startswith("21") or digits.startswith("91"):
        rest = digits[2:]
        if rest:
            return ("SERIAL", digits[:2], rest)
    # 裸串以日期 AI 开头（11/17）：必须后接 6 位真日期才算数
    if digits.startswith("11") or digits.startswith("17"):
        rest = digits[2:]
        if len(rest) == 6 and looks_like_date(rest):
            return (TYPE_MAP.get(digits[:2], "UNKNOWN"), digits[:2], rest)
        return ("UNKNOWN", digits[:2], rest)
    if len(digits) == 14:
        if is_valid_gtin14(digits):
            return ("UDI", "01", digits)
        if already_has_udi:
            return ("BATCH", "10", digits)
        return ("UNKNOWN", None, digits)
    if len(digits) == 13:
        g = "0" + digits
        if is_valid_gtin14(g):
            return ("UDI", "01", g)
        if already_has_udi:
            return ("BATCH", "10", g)
        return ("UNKNOWN", None, digits)
    if len(digits) == 12 and digits.isdigit():
        g = "00" + digits
        if is_valid_gtin14(g):
            return ("UDI", "01", g)
        if already_has_udi:
            return ("BATCH", "10", digits)
        return ("UNKNOWN", None, digits)
    if len(digits) == 8 and digits.isdigit():
        g = "000000" + digits
        if is_valid_gtin14(g):
            return ("UDI", "01", g)
        if already_has_udi:
            return ("BATCH", "10", digits)
        return ("UNKNOWN", None, digits)
    if len(digits) == 6 and looks_like_date(digits):
        return ("EXPIRY", "17", digits)
    # 含字母数字的裸串（如 SN12345、LOT2024-A）：不可能是纯数字 GTIN，
    # 已存在 UDI 时基本可确定是序列号。必须放在纯数字分支之前，否则会被 7~20 位误判。
    if any(c.isalpha() for c in digits) and 3 <= len(digits) <= 30:
        if already_has_udi:
            return ("SERIAL", "21", digits)
        return ("UNKNOWN", None, digits)
    if 7 <= len(digits) <= 20:
        if already_has_udi:
            return ("SERIAL", "21", digits)
        return ("UNKNOWN", None, digits)
    return ("UNKNOWN", None, digits)


def parse(raw: str, already_has_udi: bool = False):
    if not raw:
        return []
    s = raw.replace("(", "").replace(")", "")
    # FNC1/GS 直接删除（不留空格），避免定长值误吞分隔符空格
    s = s.replace("\x1d", "")
    # 二维码常把「两行条形码」拼成一个串，行间用 \n / \r 分隔 —— 统一转成空格
    s = s.replace("\n", " ").replace("\r", " ")
    clean = s
    fields = scan_by_ai_prefix(clean)
    if fields:
        return fields
    digits = clean.replace(" ", "").replace("\x1d", "")
    if digits:
        r = fallback_heuristic(digits, already_has_udi)
        if r:
            return [r]
    return [("UNKNOWN", None, clean.strip())]


# ── 真实样例测试 ──
CASES = [
    # (描述, 输入, already_has_udi, 期望字段列表[(type,ai,value)...])
    ("括号HRI完整", "(01)06949450446782(17)250631(10)LOT123", False,
     [("UDI", "01", "06949450446782"), ("EXPIRY", "17", "250631"), ("BATCH", "10", "LOT123")]),
    ("纯前缀拼接无分隔(最常见)", "010694945044678210LOT12317250631", False,
     [("UDI", "01", "06949450446782"), ("BATCH", "10", "LOT123"), ("EXPIRY", "17", "250631")]),
    ("FNC1分隔", "01\x1d06949450446782\x1d17\x1d250631\x1d10\x1dLOT123", False,
     [("UDI", "01", "06949450446782"), ("EXPIRY", "17", "250631"), ("BATCH", "10", "LOT123")]),
    ("裸14位GTIN", "06949450446782", False,
     [("UDI", "01", "06949450446782")]),
    ("裸14位非GTIN(应UNKNOWN或BATCH)", "12345678901234", True,
     [("BATCH", "10", "12345678901234")]),
    ("裸14位非GTIN(无UDI应UNKNOWN)", "12345678901234", False,
     [("UNKNOWN", None, "12345678901234")]),
    ("91序列号", "(01)06949450446782(91)SN998877", False,
     [("UDI", "01", "06949450446782"), ("SERIAL", "91", "SN998877")]),
    ("纯前缀含91", "010694945044678291SN998877", False,
     [("UDI", "01", "06949450446782"), ("SERIAL", "91", "SN998877")]),
    ("仅效期6位", "250631", False,
     [("EXPIRY", "17", "250631")]),
    ("UDI+批号两行拼接", "010694945044678210ABC2024", False,
     [("UDI", "01", "06949450446782"), ("BATCH", "10", "ABC2024")]),
    ("12位UPC补位", "123456789012", False,
     [("UDI", "01", "00123456789012")]),
    ("8位EAN补位", "96385074", False,
     [("UDI", "01", "00000096385074")]),
    # ── 用户思路 2：11/17 后必须 6 位真日期才算数 ──
    ("17后非日期应降级UNKNOWN", "011234567890123417999999", False,
     [("UDI", "01", "12345678901234"), ("UNKNOWN", "17", "999999")]),
    ("11后非日期应降级UNKNOWN", "011234567890123411888888", False,
     [("UDI", "01", "12345678901234"), ("UNKNOWN", "11", "888888")]),
    ("无11只有17效期(思路4)", "011234567890123417250631", False,
     [("UDI", "01", "12345678901234"), ("EXPIRY", "17", "250631")]),
    # ── 用户思路 3：21/91 序列号常在最后，裸串以21/91开头即序列号 ──
    ("裸串21开头即序列号(无UDI)", "21SN9988776655", False,
     [("SERIAL", "21", "SN9988776655")]),
    ("裸串91开头即序列号", "91ABC12345", False,
     [("SERIAL", "91", "ABC12345")]),
    # ── 用户思路 1：最多两行乱序，先扫批号行再扫UDI行也正确 ──
    ("先扫批号行(10LOT12317)再扫UDI行", "10LOT12317250631", False,
     [("BATCH", "10", "LOT123"), ("EXPIRY", "17", "250631")]),
    ("先扫UDI行再扫批号效期行", "0106949450446782", False,
     [("UDI", "01", "06949450446782")]),
    # ── 两行拼接但顺序相反（效期行在前）──
    ("两行反向拼接", "17250631100694", False,
     [("EXPIRY", "17", "250631"), ("BATCH", "10", "0694")]),
    # ── 序列号识别增强：含字母裸串，已存在 UDI 时应判 SERIAL ──
    ("含字母序列号(已有UDI)", "SN998877", True,
     [("SERIAL", "21", "SN998877")]),
    ("含字母序列号(LOT前缀,已有UDI)", "LOT2024-A99", True,
     [("SERIAL", "21", "LOT2024-A99")]),
    # ── 低置信度门禁：全是 UNKNOWN 的短码/乱码不应污染缓冲 ──
    ("乱码短串应UNKNOWN不污染", "ABC", False,
     [("UNKNOWN", None, "ABC")]),
    ("纯符号串应UNKNOWN", "@#$%", False,
     [("UNKNOWN", None, "@#$%")]),
    # ── 二维码（两行条形码拼接）归一化验证 ──
    ("二维码两行拼接(\\n分隔,括号HRI)", "(01)06949450446782(17)250631\n(10)LOT123", False,
     [("UDI", "01", "06949450446782"), ("EXPIRY", "17", "250631"), ("BATCH", "10", "LOT123")]),
    ("二维码两行拼接(\\n分隔,纯前缀)", "010694945044678217250631\n10LOT123", False,
     [("UDI", "01", "06949450446782"), ("EXPIRY", "17", "250631"), ("BATCH", "10", "LOT123")]),
]

PASS = 0
FAIL = 0
for desc, inp, ahu, expect in CASES:
    got = parse(inp, ahu)
    ok = got == expect
    PASS += ok
    FAIL += (not ok)
    status = "✅" if ok else "❌"
    print(f"{status} {desc}")
    if not ok:
        print(f"   输入: {inp!r} (alreadyHasUdi={ahu})")
        print(f"   期望: {expect}")
        print(f"   实际: {got}")

print(f"\n结果: {PASS} 通过 / {FAIL} 失败")
