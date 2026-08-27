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
                item["status"] = "ok"
            else:
                r = nmpa_client.query(udi)
                st = r.get("state")
                if st == "ok":
                    item["product_name"] = r.get("productName")
                    item["status"] = "ok"
                    db_store.save_cache(udi, r.get("productName"), r.get("specification"), r.get("companyName"))
                elif st == "pending":
                    item["product_name"] = r.get("productName")
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
            status = "unqueried"
            if udi:
                prod = db_store.lookup_product(udi)
                if prod and prod.get("product_name"):
                    product_name = prod["product_name"]
                    status = "ok"
            item = {
                "udi": udi,
                "product_name": product_name,
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
    def dict_list(self):
        return db_store.dict_list()

    def dict_add(self, udi, name, company="", spec=""):
        db_store.dict_add(udi, name, spec, company)
        return True

    def dict_import_text(self, text):
        import json
        data = json.loads(text)
        return db_store.dict_import_data(data)

    def dict_export(self):
        p = os.path.join(db_store.data_dir(),
                         "udi_dict_%s.json" % time.strftime("%Y%m%d_%H%M%S"))
        db_store.dict_export(p)
        return p
