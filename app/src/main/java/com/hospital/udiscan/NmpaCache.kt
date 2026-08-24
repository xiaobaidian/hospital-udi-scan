package com.hospital.udiscan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地 NMPA 数据与用户覆盖字典。
 *
 * 设计：
 * - udi_cache：NMPA 官方查回的结果（权威、可追溯、可重查）。命中即复用，不再联网。
 * - udi_override：用户手改/手补的字典（改名、NMPA 查不到时自建条目）。UDI 主键。
 * - 读取优先级：override > cache。即用户修正永远生效，官方数据作兜底。
 * - 多设备同步：只需导出/导入 udi_override（几十~几百条，JSON 几十 KB），官方数据各机本地查即可。
 * - 仅依赖 Android 内置 SQLite，不引 Room 等额外依赖（保持轻量、零外部下载）。
 */
object NmpaCache {

    private const val DB_NAME = "udi_cache.db"
    private const val VERSION = 2

    private const val T_CACHE = "udi_cache"
    private const val T_OVERRIDE = "udi_override"

    private const val COL_UDI = "udi"
    private const val COL_NAME = "product_name"
    private const val COL_SPEC = "specification"
    private const val COL_COMPANY = "company_name"
    private const val COL_STATE = "state"
    private const val COL_TS = "ts"

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_CACHE (" +
                        "$COL_UDI TEXT PRIMARY KEY, " +
                        "$COL_NAME TEXT, $COL_SPEC TEXT, $COL_COMPANY TEXT, " +
                        "$COL_STATE TEXT, $COL_TS INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_OVERRIDE (" +
                        "$COL_UDI TEXT PRIMARY KEY, " +
                        "$COL_NAME TEXT, $COL_SPEC TEXT, $COL_COMPANY TEXT, " +
                        "$COL_TS INTEGER)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            if (oldV < 2) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS $T_OVERRIDE (" +
                            "$COL_UDI TEXT PRIMARY KEY, " +
                            "$COL_NAME TEXT, $COL_SPEC TEXT, $COL_COMPANY TEXT, " +
                            "$COL_TS INTEGER)"
                )
            }
        }
    }

    private var helper: Helper? = null

    /** 延迟初始化（须在 UI 线程之外的首次使用前调用）。 */
    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    /** 取官方缓存；未命中返回 null。 */
    fun get(udi: String): NmpaClient.NmpaResult? {
        val h = helper ?: return null
        if (udi.isBlank()) return null
        return try {
            val db = h.readableDatabase
            val c = db.query(T_CACHE, null, "$COL_UDI = ?", arrayOf(udi), null,  null, null)
            var res: NmpaClient.NmpaResult? = null
            if (c.moveToFirst()) {
                res = NmpaClient.NmpaResult(
                    state = c.getString(c.getColumnIndexOrThrow(COL_STATE)),
                    productName = c.getString(c.getColumnIndexOrThrow(COL_NAME)).let { if (it.isEmpty()) null else it },
                    specification = c.getString(c.getColumnIndexOrThrow(COL_SPEC)).let { if (it.isEmpty()) null else it },
                    companyName = c.getString(c.getColumnIndexOrThrow(COL_COMPANY)).let { if (it.isEmpty()) null else it }
                )
            }
            c.close()
            res
        } catch (e: Exception) { null }
    }

    /** 写入官方缓存；state 为 ok/pending/skip 都存（skip 也存，避免反复联网查无记录）。 */
    fun put(udi: String, r: NmpaClient.NmpaResult) {
        val h = helper ?: return
        if (udi.isBlank()) return
        try {
            val db = h.writableDatabase
            val cv = ContentValues().apply {
                put(COL_UDI, udi)
                put(COL_NAME, r.productName ?: "")
                put(COL_SPEC, r.specification ?: "")
                put(COL_COMPANY, r.companyName ?: "")
                put(COL_STATE, r.state)
                put(COL_TS, System.currentTimeMillis())
            }
            db.insertWithOnConflict(T_CACHE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { /* 不影响主流程 */ }
    }

    /** 取用户覆盖条目；未命中返回 null。 */
    fun getOverride(udi: String): NmpaClient.NmpaResult? {
        val h = helper ?: return null
        if (udi.isBlank()) return null
        return try {
            val db = h.readableDatabase
            val c = db.query(T_OVERRIDE, null, "$COL_UDI = ?", arrayOf(udi), null, null, null)
            var res: NmpaClient.NmpaResult? = null
            if (c.moveToFirst()) {
                res = NmpaClient.NmpaResult(
                    state = "ok",
                    productName = c.getString(c.getColumnIndexOrThrow(COL_NAME)).let { if (it.isEmpty()) null else it },
                    specification = c.getString(c.getColumnIndexOrThrow(COL_SPEC)).let { if (it.isEmpty()) null else it },
                    companyName = c.getString(c.getColumnIndexOrThrow(COL_COMPANY)).let { if (it.isEmpty()) null else it }
                )
            }
            c.close()
            res
        } catch (e: Exception) { null }
    }

    /**
     * 合并读取：override 优先；无覆盖则读官方缓存（并标记来源）。
     * 返回 Pair(result, fromOverride)。result 为 null 表示该 UDI 本地完全没有数据。
     */
    fun getMerged(udi: String): Pair<NmpaClient.NmpaResult?, Boolean> {
        val ov = getOverride(udi)
        if (ov != null) return ov to true
        val ca = get(udi)
        return ca to false
    }

    /** 写入/更新用户覆盖条目（改名、查不到时手补）。 */
    fun putOverride(udi: String, name: String?, spec: String?, company: String?) {
        val h = helper ?: return
        if (udi.isBlank()) return
        try {
            val db = h.writableDatabase
            val cv = ContentValues().apply {
                put(COL_UDI, udi)
                put(COL_NAME, name ?: "")
                put(COL_SPEC, spec ?: "")
                put(COL_COMPANY, company ?: "")
                put(COL_TS, System.currentTimeMillis())
            }
            db.insertWithOnConflict(T_OVERRIDE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { /* 不影响主流程 */ }
    }

    /**
     * 批量导入覆盖条目（多设备同步用）。
     * 参数 items: List<UDI, name, spec, company>。使用事务批量写入，保证导入时扫码不卡顿。
     * 返回成功写入条数。
     */
    fun importOverrides(items: List<OverrideEntry>): Int {
        val h = helper ?: return 0
        if (items.isEmpty()) return 0
        return try {
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                for (e in items) {
                    if (e.udi.isBlank()) continue
                    val cv = ContentValues().apply {
                        put(COL_UDI, e.udi)
                        put(COL_NAME, e.name ?: "")
                        put(COL_SPEC, e.spec ?: "")
                        put(COL_COMPANY, e.company ?: "")
                        put(COL_TS, System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(T_OVERRIDE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
                items.size
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { 0 }
    }

    /** 导出覆盖条目为 JSON 字符串（多设备同步用）。 */
    fun exportOverridesJson(): String {
        val h = helper ?: return "[]"
        return try {
            val db = h.readableDatabase
            val c = db.query(T_OVERRIDE, null, null, null, null, null, "$COL_TS DESC")
            val arr = JSONArray()
            while (c.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("udi", c.getString(c.getColumnIndexOrThrow(COL_UDI)))
                    put("name", c.getString(c.getColumnIndexOrThrow(COL_NAME)))
                    put("spec", c.getString(c.getColumnIndexOrThrow(COL_SPEC)))
                    put("company", c.getString(c.getColumnIndexOrThrow(COL_COMPANY)))
                })
            }
            c.close()
            arr.toString(2)
        } catch (e: Exception) { "[]" }
    }

    /** 覆盖条目总数（用于 UI 展示字典规模）。 */
    fun overrideCount(): Int {
        val h = helper ?: return 0
        return try {
            val db = h.readableDatabase
            val c = db.rawQuery("SELECT COUNT(*) FROM $T_OVERRIDE", null)
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            n
        } catch (e: Exception) { 0 }
    }

    /** 导出官方缓存（NMPA 查回的结果）为 JSON 字符串。 */
    fun exportCacheJson(): String {
        val h = helper ?: return "[]"
        return try {
            val db = h.readableDatabase
            val c = db.query(T_CACHE, null, null, null, null, null, "$COL_TS DESC")
            val arr = JSONArray()
            while (c.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("udi", c.getString(c.getColumnIndexOrThrow(COL_UDI)))
                    put("name", c.getString(c.getColumnIndexOrThrow(COL_NAME)))
                    put("spec", c.getString(c.getColumnIndexOrThrow(COL_SPEC)))
                    put("company", c.getString(c.getColumnIndexOrThrow(COL_COMPANY)))
                    put("state", c.getString(c.getColumnIndexOrThrow(COL_STATE)))
                })
            }
            c.close()
            arr.toString(2)
        } catch (e: Exception) { "[]" }
    }

    /** 批量导入官方缓存条目（合并写入，已存在则覆盖）。 */
    fun importCache(items: List<CacheEntry>): Int {
        val h = helper ?: return 0
        if (items.isEmpty()) return 0
        return try {
            val db = h.writableDatabase
            db.beginTransaction()
            try {
                for (e in items) {
                    if (e.udi.isBlank()) continue
                    val cv = ContentValues().apply {
                        put(COL_UDI, e.udi)
                        put(COL_NAME, e.name ?: "")
                        put(COL_SPEC, e.spec ?: "")
                        put(COL_COMPANY, e.company ?: "")
                        put(COL_STATE, e.state ?: "ok")
                        put(COL_TS, System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(T_CACHE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
                items.size
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { 0 }
    }

    /** 官方缓存全部条目（用于查看 / 编辑）。 */
    fun getAllCache(): List<NmpaResultLite> {
        val h = helper ?: return emptyList()
        val out = mutableListOf<NmpaResultLite>()
        try {
            val db = h.readableDatabase
            val c = db.query(T_CACHE, null, null, *null, null, null, "$COL_TS DESC")
            while (c.moveToNext()) {
                out.add(NmpaResultLite(
                    udi = c.getString(c.getColumnIndexOrThrow(COL_UDI)),
                    name = c.getString(c.getColumnIndexOrThrow(COL_NAME)).let { if (it.isEmpty()) null else it },
                    spec = c.getString(c.getColumnIndexOrThrow(COL_SPEC)).let { if (it.isEmpty()) null else it },
                    company = c.getString(c.getColumnIndexOrThrow(COL_COMPANY)).let { if (it.isEmpty()) null else it },
                    state = c.getString(c.getColumnIndexOrThrow(COL_STATE))
                ))
            }
            c.close()
        } catch (e: Exception) { }
        return out
    }

    /** 删除单条官方缓存（按 UDI）。 */
    fun deleteCache(udi: String) {
        val h = helper ?: return
        try { h.writableDatabase.delete(T_CACHE, "$COL_UDI = ?", arrayOf(udi)) } catch (_: Exception) {}
    }

    data class CacheEntry(
        val udi: String,
        val name: String?,
        val spec: String?,
        val company: String?,
        val state: String?
    )

    data class NmpaResultLite(
        val udi: String,
        val name: String?,
        val spec: String?,
        val company: String?,
        val state: String
    )

    /** 用户覆盖字典全部条目（用于查看 / 编辑）。 */
    fun getAllOverrides(): List<NmpaResultLite> {
        val h = helper ?: return emptyList()
        val out = mutableListOf<NmpaResultLite>()
        try {
            val db = h.readableDatabase
            val c = db.query(T_OVERRIDE, null, null, null, null, null, "$COL_TS DESC")
            while (c.moveToNext()) {
                out.add(NmpaResultLite(
                    udi = c.getString(c.getColumnIndexOrThrow(COL_UDI)),
                    name = c.getString(c.getColumnIndexOrThrow(COL_NAME)).let { if (it.isEmpty()) null else it },
                    spec = c.getString(c.getColumnIndexOrThrow(COL_SPEC)).let { if (it.isEmpty()) null else it },
                    company = c.getString(c.getColumnIndexOrThrow(COL_COMPANY)).let { if (it.isEmpty()) null else it },
                    state = "custom"
                ))
            }
            c.close()
        } catch (e: Exception) { }
        return out
    }

    /** 删除单条用户覆盖（按 UDI）。 */
    fun deleteOverride(udi: String) {
        val h = helper ?: return
        try { h.writableDatabase.delete(T_OVERRIDE, "$COL_UDI = ?", arrayOf(udi)) } catch (_: Exception) {}
    }

    /** 清空全部（官方缓存 + 用户覆盖）。供"清空缓存"入口使用。 */
    fun clearAll() {
        val h = helper ?: return
        try {
            val db = h.writableDatabase
            db.delete(T_CACHE, null, null)
            db.delete(T_OVERRIDE,  null, null)
        } catch (_: Exception) {}
    }

    /** 只清空用户覆盖字典（保留官方缓存）。 */
    fun clearOverrides() {
        val h = helper ?: return
        try { h.writableDatabase.delete(T_OVERRIDE, null, null) } catch (_: Exception) {}
    }

    /** 官方缓存条目数。 */
    fun count(): Int {
        val h = helper ?: return 0
        return try {
            val db = h.readableDatabase
            val c = db.rawQuery("SELECT COUNT(*) FROM $T_CACHE", null)
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            n
        } catch (_: Exception) { 0 }
    }

    data class OverrideEntry(
        val udi: String,
        val name: String?,
        val spec: String?,
        val company: String?
    )
}
