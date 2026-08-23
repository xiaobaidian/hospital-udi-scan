package com.hospital.udiscan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地 NMPA 查询结果缓存。
 *
 * 设计：
 * - 表 udi_cache 以 udi(主键) → 名称/规格/厂家/状态/时间。
 * - 命中即直接返回，不再联网；解决"重复扫码同款器械反复联网"与"无网环境复用"的问题。
 * - 缓存不过期（器械主数据变化极慢）；如需刷新可在 App 内清空。
 * - 仅依赖 Android 内置 SQLite，不引入 Room 等额外依赖（保持轻量、零外部下载）。
 */
object NmpaCache {

    private const val DB_NAME = "udi_cache.db"
    private const val TABLE = "udi_cache"
    private const val COL_UDI = "udi"
    private const val COL_NAME = "product_name"
    private const val COL_SPEC = "specification"
    private const val COL_COMPANY = "company_name"
    private const val COL_STATE = "state"
    private const val COL_TS = "ts"

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                        "$COL_UDI TEXT PRIMARY KEY, " +
                        "$COL_NAME TEXT, $COL_SPEC TEXT, $COL_COMPANY TEXT, " +
                        "$COL_STATE TEXT, $COL_TS INTEGER)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private var helper: Helper? = null

    /** 延迟初始化（必须在 UI 线程之外的首次使用前调用，或在 Activity 里初始化）。 */
    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    /** 取缓存；未命中返回 null。 */
    fun get(udi: String): NmpaClient.NmpaResult? {
        val h = helper ?: return null
        if (udi.isBlank()) return null
        return try {
            val db = h.readableDatabase
            val c = db.query(
                TABLE, null, "$COL_UDI = ?", arrayOf(udi), null, null, null
            )
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
        } catch (e: Exception) {
            null
        }
    }

    /** 写入缓存；state 为 ok/pending/skip 时都存（skip 也存，避免反复联网查无记录）。 */
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
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            // 缓存写入失败不影响主流程（仍可在内存中使用本次结果）
        }
    }

    /** 清空缓存（供 UI 提供"刷新/清空缓存"入口时使用）。 */
    fun clearAll() {
        val h = helper ?: return
        try { h.writableDatabase.delete(TABLE, null, null) } catch (_: Exception) {}
    }

    fun count(): Int {
        val h = helper ?: return 0
        return try {
            val db = h.readableDatabase
            val c = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            n
        } catch (_: Exception) { 0 }
    }
}
