# -*- coding: utf-8 -*-
"""本地 SQLite 存储：自定义字典 + NMPA 缓存 + 盘点清单（持久化）。

三张表：
  T_DICT    自定义字典（用户手动维护；NMPA 查不到时在此手动添加，优先级最高）
  T_CACHE   NMPA 官方字典 / 联网查询缓存（只读、不可手改，仅作兜底）
  T_LIST    盘点清单（关掉再开仍在）

字段说明（自定义字典）：
  udi        主键（UDI/GTIN-14）；扫码按它查
  code       物资编码（供采平台商品编码，可作备选查码键）
  name       产品名称
  model      型号 / 规格型号（盘点到处都关心，列为关键字段）
  company    生产厂家
  brand      品牌
  specification  兼容旧字段，始终 = model

便携策略：数据库放 exe 同目录（udi_cache.db），用户拷到哪都能用。
"""
import os
import sqlite3
import json
import csv
import sys
import threading

_lock = threading.Lock()


def data_dir():
    """exe 同目录（打包后为 sys.executable 所在）；失败回退到脚本目录。"""
    try:
        if getattr(sys, "frozen", False):
            base = os.path.dirname(sys.executable)
        else:
            base = os.path.dirname(os.path.abspath(__file__))
    except Exception:
        base = os.getcwd()
    return base


DB_PATH = os.path.join(data_dir(), "udi_cache.db")


def _conn():
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init():
    conn = _conn()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS T_CACHE (
            udi TEXT PRIMARY KEY,
            product_name TEXT,
            specification TEXT,
            company_name TEXT,
            updated_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS T_DICT (
            udi TEXT PRIMARY KEY,
            code TEXT,
            name TEXT,
            model TEXT,
            company TEXT,
            brand TEXT,
            specification TEXT,
            updated_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS T_LIST (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            udi TEXT,
            product_name TEXT,
            model TEXT,
            batch TEXT,
            expiry TEXT,
            production TEXT,
            serial TEXT,
            qty INTEGER DEFAULT 1,
            status TEXT,
            raw TEXT,
            created_at INTEGER
        );
    """)
    conn.commit()
    conn.close()


def _now():
    return int(time.time()) if (time := __import__("time")) else 0


# ---------------- NMPA 缓存 / 覆盖 ----------------
def lookup_product(udi):
    """按 UDI 查产品信息：先自定义字典，再 NMPA 缓存。返回 dict 或 None。"""
    conn = _conn()
    try:
        r = conn.execute(
            "SELECT name,model,company,brand FROM T_DICT WHERE udi=?",
            (udi,)).fetchone()
        if r:
            return {"source": "dict", "product_name": r[0], "model": r[1],
                    "company_name": r[2], "brand": r[3]}
        r = conn.execute(
            "SELECT product_name,specification,company_name FROM T_CACHE WHERE udi=?",
            (udi,)).fetchone()
        if r:
            return {"source": "cache", "product_name": r[0], "model": r[1],
                    "company_name": r[2], "brand": ""}
    finally:
        conn.close()
    return None


def lookup_any(q):
    """按 UDI / 物资编码 / 型号 任一模糊查自定义字典，返回首条或 None。"""
    q = (q or "").strip()
    if not q:
        return None
    conn = _conn()
    try:
        r = conn.execute(
            "SELECT udi,name,model,company,brand FROM T_DICT "
            "WHERE udi=? OR code=? OR model=? LIMIT 1",
            (q, q, q)).fetchone()
        if r:
            return {"source": "dict", "udi": r[0], "product_name": r[1],
                    "model": r[2], "company_name": r[3], "brand": r[4]}
    finally:
        conn.close()
    return None


def save_cache(udi, product_name, specification, company_name):
    conn = _conn()
    try:
        conn.execute(
            "INSERT OR REPLACE INTO T_CACHE(udi,product_name,specification,company_name,updated_at) VALUES(?,?,?,?,?)",
            (udi, product_name, specification, company_name, int(__import__("time").time())))
        conn.commit()
    finally:
        conn.close()


# ---------------- 自定义字典（T_DICT） ----------------
def dict_list_custom():
    conn = _conn()
    try:
        rows = conn.execute(
            "SELECT udi,code,name,model,company,brand FROM T_DICT ORDER BY udi").fetchall()
    finally:
        conn.close()
    return [{"udi": r[0], "code": r[1] or "", "name": r[2] or "", "model": r[3] or "",
             "company": r[4] or "", "brand": r[5] or ""} for r in rows]


def dict_list_cache():
    conn = _conn()
    try:
        rows = conn.execute(
            "SELECT udi,product_name,specification,company_name FROM T_CACHE ORDER BY udi").fetchall()
    finally:
        conn.close()
    return [{"udi": r[0], "product_name": r[1] or "", "specification": r[2] or "",
             "company_name": r[3] or ""} for r in rows]


def dict_search(q):
    q = (q or "").strip()
    if not q:
        return dict_list_custom()
    like = "%" + q + "%"
    conn = _conn()
    try:
        rows = conn.execute(
            "SELECT udi,code,name,model,company,brand FROM T_DICT "
            "WHERE udi LIKE ? OR code LIKE ? OR name LIKE ? OR model LIKE ? OR company LIKE ? OR brand LIKE ? "
            "ORDER BY udi",
            (like, like, like, like, like, like)).fetchall()
    finally:
        conn.close()
    return [{"udi": r[0], "code": r[1] or "", "name": r[2] or "", "model": r[3] or "",
             "company": r[4] or "", "brand": r[5] or ""} for r in rows]


def dict_add(udi, name, model="", company="", code="", brand=""):
    udi = (udi or "").strip()
    if not udi:
        return False
    conn = _conn()
    try:
        conn.execute(
            "INSERT OR REPLACE INTO T_DICT(udi,code,name,model,company,brand,specification,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (udi, code, name, model, company, brand, model, int(__import__("time").time())))
        conn.commit()
    finally:
        conn.close()
    return True


def dict_delete(udi):
    conn = _conn()
    try:
        conn.execute("DELETE FROM T_DICT WHERE udi=?", (udi,))
        conn.commit()
    finally:
        conn.close()
    return True


def dict_count():
    conn = _conn()
    try:
        n = conn.execute("SELECT COUNT(*) FROM T_DICT").fetchone()[0]
    finally:
        conn.close()
    return n


def dict_import_official(path):
    """导入 NMPA 官方字典结果到 NMPA 缓存表 T_CACHE（只读兜底，非自定义字典）。

    仅取 status='ok' 且 udi 非空的确认条目；按 udi 去重；字段映射：
      udi<-udi, product_name<-name, specification<-model(规格型号),
      company_name<-nmpa_mfr||company||brand。
    返回新增条数。
    """
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    items = data.values() if isinstance(data, dict) else data
    added = 0
    conn = _conn()
    try:
        for it in items:
            if not isinstance(it, dict):
                continue
            if it.get("status") != "ok":
                continue
            udi = (it.get("udi") or "").strip()
            if not udi:
                continue
            name = (it.get("name") or "").strip()
            model = (it.get("model") or "").strip()
            company = (it.get("nmpa_mfr") or it.get("company") or it.get("brand") or "").strip()
            conn.execute(
                "INSERT OR REPLACE INTO T_CACHE(udi,product_name,specification,company_name,updated_at) "
                "VALUES(?,?,?,?,?)",
                (udi, name, model, company, int(__import__("time").time())))
            added += 1
        conn.commit()
    finally:
        conn.close()
    return added


def dict_import_csv(path):
    """导入 CSV（列名自适应）。映射常见中文/英文表头。返回新增条数。"""
    import csv as _csv
    added = 0
    conn = _conn()
    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as f:
            reader = _csv.DictReader(f)
            for row in reader:
                norm = {k.strip().lower(): v for k, v in row.items() if k}
                udi = _pick(norm, ["udi", "udi码", "gtin", "条码", "udi条码"]) or ""
                udi = udi.strip()
                if not udi:
                    continue
                name = _pick(norm, ["name", "名称", "产品名称", "品名"]) or ""
                model = _pick(norm, ["model", "型号", "规格型号", "规格"]) or ""
                company = _pick(norm, ["company", "厂家", "生产厂家", "生产企业", "公司"]) or ""
                code = _pick(norm, ["code", "物资编码", "编码", "物料编码"]) or ""
                brand = _pick(norm, ["brand", "品牌"]) or ""
                conn.execute(
                    "INSERT OR REPLACE INTO T_DICT(udi,code,name,model,company,brand,specification,updated_at) "
                    "VALUES(?,?,?,?,?,?,?,?)",
                    (udi, code, name, model, company, brand, model, int(__import__("time").time())))
                added += 1
        conn.commit()
    finally:
        conn.close()
    return added


def dict_import_json(path):
    """导入通用字典 JSON（list[dict] 或 {udi:{...}}）。按 udi 键或 udi 字段。"""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    items = data.values() if isinstance(data, dict) else data
    added = 0
    conn = _conn()
    try:
        for it in items:
            if not isinstance(it, dict):
                continue
            udi = (it.get("udi") or "").strip()
            if not udi:
                continue
            name = (it.get("name") or it.get("product_name") or "").strip()
            model = (it.get("model") or it.get("specification") or "").strip()
            company = (it.get("company") or it.get("company_name") or "").strip()
            code = (it.get("code") or "").strip()
            brand = (it.get("brand") or "").strip()
            conn.execute(
                "INSERT OR REPLACE INTO T_DICT(udi,code,name,model,company,brand,specification,updated_at) "
                "VALUES(?,?,?,?,?,?,?,?)",
                (udi, code, name, model, company, brand, model, int(__import__("time").time())))
            added += 1
        conn.commit()
    finally:
        conn.close()
    return added


def _pick(norm, keys):
    for k in keys:
        if k in norm and (norm[k] or "").strip():
            return norm[k].strip()
    return ""


def dict_export(path):
    data = dict_list_custom()
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return len(data)


# ---------------- 盘点清单 ----------------
def list_items():
    conn = _conn()
    try:
        rows = conn.execute(
            "SELECT id,udi,product_name,model,batch,expiry,production,serial,qty,status,raw "
            "FROM T_LIST ORDER BY id").fetchall()
    finally:
        conn.close()
    return [{"id": r[0], "udi": r[1], "product_name": r[2], "model": r[3] or "",
             "batch": r[4], "expiry": r[5], "production": r[6], "serial": r[7],
             "qty": r[8], "status": r[9], "raw": r[10]} for r in rows]


def add_item(item):
    conn = _conn()
    try:
        cur = conn.execute(
            "INSERT INTO T_LIST(udi,product_name,model,batch,expiry,production,serial,qty,status,raw,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (item.get("udi"), item.get("product_name"), item.get("model"),
             item.get("batch"), item.get("expiry"), item.get("production"),
             item.get("serial"), int(item.get("qty", 1)), item.get("status", "pending"),
             item.get("raw", ""), int(__import__("time").time())))
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def update_qty(item_id, qty):
    conn = _conn()
    try:
        conn.execute("UPDATE T_LIST SET qty=? WHERE id=?", (int(qty), int(item_id)))
        conn.commit()
    finally:
        conn.close()


def delete_item(item_id):
    conn = _conn()
    try:
        conn.execute("DELETE FROM T_LIST WHERE id=?", (int(item_id),))
        conn.commit()
    finally:
        conn.close()


def clear_list():
    conn = _conn()
    try:
        conn.execute("DELETE FROM T_LIST")
        conn.commit()
    finally:
        conn.close()


def stats():
    conn = _conn()
    try:
        total = conn.execute("SELECT COUNT(*) FROM T_LIST").fetchone()[0]
        ok = conn.execute("SELECT COUNT(*) FROM T_LIST WHERE status='ok'").fetchone()[0]
        pending = conn.execute("SELECT COUNT(*) FROM T_LIST WHERE status='pending'").fetchone()[0]
        none = conn.execute("SELECT COUNT(*) FROM T_LIST WHERE status='none'").fetchone()[0]
    finally:
        conn.close()
    return {"total": total, "ok": ok, "pending": pending, "none": none}


# ---------------- 导出 ----------------
def export_json(path):
    data = list_items()
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return len(data)


def export_csv(path):
    data = list_items()
    cols = ["udi", "product_name", "model", "batch", "expiry", "production", "serial", "qty", "status"]
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for it in data:
            w.writerow({c: it.get(c, "") for c in cols})
    return len(data)


init()
