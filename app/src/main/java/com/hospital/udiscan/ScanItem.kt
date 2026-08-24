package com.hospital.udiscan

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条已录入的扫码记录（对应一个 UDI 及同组 GS1 字段）。
 */
data class ScanItem(
    val id: String,
    val udiDi: String?,
    var batch: String?,        // (10) 批号
    var expiry: String?,       // (17) 效期 原始 YYMMDD
    var production: String?,   // (11) 生产日期
    var serial: String?,       // (21) 或 (91) 序列号
    var serialAi: String?,     // "21" / "91"
    var productName: String?,
    var specification: String?,
    var companyName: String?,
    var nmpaState: String,     // ok | local | pending | skip | err | none
    var quantity: Int,
    val raw: String,
    val scannedAt: String
) {
    fun displayName(): String =
        productName ?: (if (udiDi.isNullOrEmpty()) "（无UDI）" else "UDI $udiDi")

    fun detailLine(): String {
        val parts = mutableListOf<String>()
        batch?.let { parts.add("批号:$it") }
        expiry?.let { parts.add("效期:${Gs1Parser.formatDateYYMMDD(it) ?: it}") }
        production?.let { parts.add("生产:${Gs1Parser.formatDateYYMMDD(it) ?: it}") }
        serial?.let { parts.add("序列(${serialAi ?: "?"}):$it") }
        if (parts.isEmpty()) parts.add("（仅原始条码）")
        return parts.joinToString("   ")
    }

    fun metaLine(): String {
        val parts = mutableListOf<String>()
        companyName?.let { parts.add("厂家:$it") }
        specification?.let { parts.add("规格:$it") }
        if (!udiDi.isNullOrEmpty()) parts.add("UDI:$udiDi")
        parts.add("数量:$quantity")
        return parts.joinToString("   ")
    }

    companion object {
        fun nowStamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
    }

    /**
     * 就地更新「本条清单内容」字段（批号/效期/生产/序列/数量）。
     * 这些字段属于单条记录，不写字典、不同步其他同 UDI 条目。
     */
    fun updateListFields(
        batch: String?, expiry: String?, production: String?,
        serial: String?, serialAi: String?, quantity: Int?
    ) {
        batch?.let { this.batch = it.trim().let { v -> if (v.isEmpty()) null else v } }
        expiry?.let { this.expiry = it.trim().let { v -> if (v.isEmpty()) null else v } }
        production?.let { this.production = it.trim().let { v -> if (v.isEmpty()) null else v } }
        serial?.let { this.serial = it.trim().let { v -> if (v.isEmpty()) null else v } }
        serialAi?.let { this.serialAi = it }
        quantity?.let { if (it >= 1) this.quantity = it }
    }
}
