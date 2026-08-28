# -*- coding: utf-8 -*-
"""JS <-> Python 桥接层（暴露给 pywebview 的 API）。

所有方法都返回可 JSON 序列化对象；pywebview 自动转成 JS Promise。
"""
import os
import time

import gs1_parser
import nmpa_client
import db_store


class Api:
    def parse(self, raw):
        """仅解析预览，不写清单。"""
        return gs1_parser.parse(raw, already_has_udi=True)

    def submit(self, raw):
        """解析一条条码 → 校验完整性 → 查 NMPA（带缓存）→ 写入清单。

        不满足「UDI 在开头 + 含 UDI/效期/批号」时直接返回失败，不入库。
        """
        raw = (raw or "").strip()
        if not raw:
            return {"ok": False, "error": "empty"}
        res = gs1_parser.parse(raw, already_has_udi=True)
        if not res.get("complete"):
            return {"ok": False, "error": res.get("error") or "解析不完整",
                    "fields": res["fields"]}
        fields = res["fields"]
        m = {f["type"]: f["value"] for f in fields}
        udi = m.get("UDI")
        item = {
            "udi": udi,
            "product_name": None,
            "batch": m.get("BATCH"),
            "expiry": m.get("EXPIRY"),
            "production": m.get("PROD_DATE"),
            "serial": m.get("SERIAL"),
            "qty": 1,
            "status": "pending",
            "raw": raw,
        }
        if udi:
            prod = db_store.lookup_product(udi)
            if prod and prod.get("product_name"):
                item["product_name"] = prod["product_name"]
                item["model"] = prod.get("model") or ""
                item["status"] = "ok"
            else:
                r = nmpa_client.query(udi)
                st = r.get("state")
                if st == "ok":
                    item["product_name"] = r.get("productName")
                    item["model"] = r.get("specification") or ""
                    item["status"] = "ok"
                    db_store.save_cache(udi, r.get("productName"), r.get("specification"), r.get("companyName"))
                elif st == "pending":
                    item["product_name"] = r.get("productName")
                    item["model"] = r.get("specification") or ""
                    item["status"] = "pending"
                    db_store.save_cache(udi, r.get("productName"), r.get("specification"), r.get("companyName"))
                elif st == "skip":
                    item["status"] = "none"
                else:
                    item["status"] = "err"
        db_store.add_item(item)
        return {"ok": True, "item": item, "fields": fields}

    def list(self):
        return db_store.list_items()

    def batch_parse(self, text):
        """批量解析（只解析、不入库、不联网）。每条返回完整性与失败原因。"""
        lines = [l.strip() for l in (text or "").split("\n") if l.strip()]
        out = []
        for l in lines:
            res = gs1_parser.parse(l, already_has_udi=True)
            out.append({
                "raw": l,
                "fields": res["fields"],
                "complete": res["complete"],
                "missing": res["missing"],
                "error": res["error"],
            })
        return out

    def add_batch(self, raws):
        """把已通过校验的条码批量入库。仅用本地缓存补全产品名（不联网，保持快）。
        返回实际加入条数。"""
        added = 0
        for raw in (raws or []):
            raw = (raw or "").strip()
            if not raw:
                continue
            res = gs1_parser.parse(raw, already_has_udi=True)
            if not res.get("complete"):
                continue
            m = {f["type"]: f["value"] for f in res["fields"]}
            udi = m.get("UDI")
            product_name = None
            model = ""
            status = "unqueried"
            if udi:
                prod = db_store.lookup_product(udi)
                if prod and prod.get("product_name"):
                    product_name = prod["product_name"]
                    model = prod.get("model") or ""
                    status = "ok"
            item = {
                "udi": udi,
                "product_name": product_name,
                "model": model,
                "batch": m.get("BATCH"),
                "expiry": m.get("EXPIRY"),
                "production": m.get("PROD_DATE"),
                "serial": m.get("SERIAL"),
                "qty": 1,
                "status": status,
                "raw": raw,
            }
            db_store.add_item(item)
            added += 1
        return {"added": added}

    def del_item(self, item_id):
        db_store.delete_item(item_id)
        return True

    def set_qty(self, item_id, qty):
        db_store.update_qty(item_id, int(qty))
        return True

    def clear(self):
        db_store.clear_list()
        return True

    def export_json(self):
        p = os.path.join(db_store.data_dir(),
                         "udi_inventory_%s.json" % time.strftime("%Y%m%d_%H%M%S"))
        db_store.export_json(p)
        return p

    def export_csv(self):
        p = os.path.join(db_store.data_dir(),
                         "udi_inventory_%s.csv" % time.strftime("%Y%m%d_%H%M%S"))
        db_store.export_csv(p)
        return p

    def stats(self):
        return db_store.stats()

    # ---------------- 字典库 ----------------
    def dict_custom(self):
        return db_store.dict_list_custom()

    def dict_cache(self):
        return db_store.dict_list_cache()

    def dict_cache_search(self, q):
        return db_store.dict_cache_search(q)

    def dict_search(self, q):
        return db_store.dict_search(q)

    def dict_count(self):
        return db_store.dict_count()

    def dict_add(self, udi, name, model="", company="", code="", brand=""):
        ok = db_store.dict_add(udi, name, model, company, code, brand)
        return {"ok": ok}

    def dict_delete(self, udi):
        db_store.dict_delete(udi)
        return True

    def import_official(self, path):
        """导入 NMPA 官方字典结果（status=ok 且带 UDI 的确认条目）。"""
        try:
            n = db_store.dict_import_official(path)
            return {"ok": True, "added": n}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def import_file(self, path):
        """按扩展名自动选 CSV / JSON 导入到自定义字典。"""
        try:
            if path.lower().endswith(".csv"):
                n = db_store.dict_import_csv(path)
            else:
                n = db_store.dict_import_json(path)
            return {"ok": True, "added": n}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def pick_import_official(self):
        """弹出系统文件框，选 NMPA 官方字典 json 导入。"""
        import webview
        paths = webview.windows[0].create_file_dialog(
            webview.OPEN_DIALOG, allow_multiple=False,
            file_types=("NMPA 官方字典 (*.json)", "All files (*.*)"))
        if not paths:
            return {"ok": True, "added": 0, "cancelled": True}
        try:
            n = db_store.dict_import_official(paths[0])
            return {"ok": True, "added": n}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def pick_import_file(self):
        """弹出系统文件框，选 CSV/JSON 导入到自定义字典。"""
        import webview
        paths = webview.windows[0].create_file_dialog(
            webview.OPEN_DIALOG, allow_multiple=False,
            file_types=("CSV/JSON (*.csv;*.json)", "All files (*.*)"))
        if not paths:
            return {"ok": True, "added": 0, "cancelled": True}
        p = paths[0]
        try:
            ext = os.path.splitext(p)[1].lower()
            n = db_store.dict_import_csv(p) if ext == ".csv" else db_store.dict_import_json(p)
            return {"ok": True, "added": n}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def dict_export(self):
        p = os.path.join(db_store.data_dir(),
                         "udi_dict_%s.json" % time.strftime("%Y%m%d_%H%M%S"))
        db_store.dict_export(p)
        return p

    def dict_cache_export(self):
        p = os.path.join(db_store.data_dir(),
                         "udi_nmpa_cache_%s.json" % time.strftime("%Y%m%d_%H%M%S"))
        db_store.dict_cache_export(p)
        return p
