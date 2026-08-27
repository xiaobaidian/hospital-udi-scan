# -*- coding: utf-8 -*-
"""本地 SQLite 存储：NMPA 缓存 + 用户覆盖字典 + 盘点清单（持久化）。

三张表：
  T_CACHE   官方 NMPA 查询结果缓存（查过的 UDI 落库，下次直接读库不联网）
  T_OVERRIDE 用户手动覆盖（优先级高于缓存）
  T_LIST    盘点清单（关掉再开仍在）

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
        CREATE TABLE IF NOT EXISTS T_OVERRIDE (
            udi TEXT PRIMARY KEY,
            product_name TEXT,
            specification TEXT,
            company_name TEXT,
            updated_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS T_LIST (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            udi TEXT,
            product_name TEXT,
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
    """查产品信息：先覆盖表，再缓存表。返回 dict 或 None。"""
    conn = _conn()
    try:
        r = conn.execute(
            "SELECT product_name,specification,company_name FROM T_OVERRIDE WHERE udi=?",
            (udi,)).fetchone()
        if r:
            return {"source": "override", "product_name": r[0], "specification": r[1], "company_name": r[2]}
        r = conn.execute(
            "SELECT product_name,specification,company_name FROM T_CACHE WHERE udi=?",
            (udi,)).fetchone()
        if r:
            return {"source": "cache", "product_name": r[0], "specification": r[1], "company_name": r[2]}
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


def dict_list():
    conn = _conn()
    try:
        rows = conn.execute(
            "SELECT udi,product_name,specification,company_name,'override' FROM T_OVERRIDE "
            "UNION ALL "
            "SELECT udi,product_name,specification,company_name,'cache' FROM T_CACHE "
            "ORDER BY udi").fetchall()
    finally:
        conn.close()
    return [{"udi": r[0], "product_name": r[1], "specification": r[2],
             "company_name": r[3], "source": r[4]} for r in rows]


def dict_add(udi, product_name, specification="", company_name=""):
    conn = _conn()
    try:
        conn.execute(
            "INSERT OR REPLACE INTO T_OVERRIDE(udi,product_name,specification,company_name,updated_at) VALUES(?,?,?,?,?)",
            (udi, product_name, specification, company_name, int(__import__("time").time())))
        conn.commit()
    finally:
        conn.close()


def dict_import_data(data):
    """从已解析的 dict/list 导入覆盖字典（追加，不删已有）。返回导入条数。"""
    if isinstance(data, dict):
        data = [data]
    n = 0
    conn = _conn()
    try:
        for item in data:
            udi = (item.get("udi") or "").strip()
            if not udi:
                continue
            conn.execute(
                "INSERT OR REPLACE INTO T_OVERRIDE(udi,product_name,specification,company_name,updated_at) VALUES(?,?,?,?,?)",
                (udi, item.get("product_name", ""), item.get("specification", ""),
                 item.get("company_name", ""), int(__import__("time").time())))
            n += 1
        conn.commit()
    finally:
        conn.close()
    return n


def dict_import(path):
    """从 JSON 文件导入覆盖字典。返回导入条数。"""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return dict_import_data(data)


def dict_export(path):
    data = dict_list()
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return len(data)


# ---------------- 盘点清单 ----------------
def list_items():
    conn = _conn()
    try:
        rows = conn.execute(
            "SELECT id,udi,product_name,batch,expiry,production,serial,qty,status,raw "
            "FROM T_LIST ORDER BY id").fetchall()
    finally:
        conn.close()
    return [{"id": r[0], "udi": r[1], "product_name": r[2], "batch": r[3],
             "expiry": r[4], "production": r[5], "serial": r[6], "qty": r[7],
             "status": r[8], "raw": r[9]} for r in rows]


def add_item(item):
    conn = _conn()
    try:
        cur = conn.execute(
            "INSERT INTO T_LIST(udi,product_name,batch,expiry,production,serial,qty,status,raw,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (item.get("udi"), item.get("product_name"), item.get("batch"),
             item.get("expiry"), item.get("production"), item.get("serial"),
             int(item.get("qty", 1)), item.get("status", "pending"),
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
    cols = ["udi", "product_name", "batch", "expiry", "production", "serial", "qty", "status"]
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for it in data:
            w.writerow({c: it.get(c, "") for c in cols})
    return len(data)


init()
