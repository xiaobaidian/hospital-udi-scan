package com.hospital.udiscan

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * GS1 应用标识符解析 + 字段语义判定。
 *
 * 设计目标（针对「字段没区分好 / 先扫到第二行」的痛点）：
 * 1. 解析结果带「字段语义类型」，UI 可以明确显示「识别为：UDI / 批号 / 效期 / 序列号 / 未知」。
 * 2. 每条扫码内容独立判定语义，再按类型并入缓冲——即使先扫到批号/序列号，后续扫到 UDI 也能正确归位，
 *    而不是「按出现顺序填坑」导致错位。
 * 3. 支持两种输入：
 *    a) 括号化 HRI，如 (01)06949450446782(17)250631(10)LOT123
 *    b) FNC1(ASCII 29) 分隔的原始串，如 01 06949450446782 <GS> 17 250631 <GS> 10 LOT123
 *
 * 语义判定规则（当没有 FNC1、也没有括号、纯数字时）：
 * - 14 位数字 → 默认当作 UDI/GTIN(01)；但若缓冲里已有 UDI 且本段更像批号/序列号，则降级处理。
 * - 6 位数字且「像日期」（首两位 0-31 且后两位 1-12） → 效期(17) / 生产(11) 候选。
 * - 其余 → 未知（UI 提示用户手动选择），不再无脑塞序列号。
 *
 * 重点：部分厂家序列号用 (91) 而非标准 (21)，二者都按序列号处理。
 */
object Gs1Parser {

    /** 字段语义类型，供 UI 显示与合并。 */
    enum class FieldType {
        UDI,        // (01) 医疗器械唯一标识 / GTIN
        BATCH,      // (10) 批号
        EXPIRY,     // (17) 有效期至
        PROD_DATE,  // (11) 生产日期
        SERIAL,     // (21) 或 (91) 序列号
        UNKNOWN     // 无法判定，交由用户选择
    }

    // 定长 AI（字符数）
    private val FIXED = mapOf(
        "01" to 14, "02" to 14,
        "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6,
        "8008" to 8
    )

    // 变长 AI（我们关心的集合；命中即按"变长"处理）
    private val VAR = setOf(
        "10", "21", "91", "20", "240", "241", "242", "250",
        "400", "401", "422", "710", "8004"
    )

    private fun isKnownAi(code: String): Boolean = FIXED.containsKey(code) || VAR.contains(code)

    data class Field(
        val type: FieldType,
        val ai: String?,
        val value: String
    )

    data class Gs1Result(
        val fields: List<Field>,     // 保留顺序，方便 UI 展示「本次扫到了什么」
        val raw: String
    )

    /**
     * 解析入口。
     * @param raw 原始扫码字符串
     * @param isGs1 zxing 是否标记为 GS1（部分机型 FNC1 被剥离，仅作参考）
     * @param alreadyHasUdi 当前缓冲是否已存在 UDI——用于纯数字歧义判定（已有 UDI 时，新的 14 位数字优先视为批号/序列号）
     */
    fun parse(raw: String, isGs1: Boolean = false, alreadyHasUdi: Boolean = false): Gs1Result {
        if (raw.isEmpty()) return Gs1Result(emptyList(), raw)

        val fnc1 = 29.toChar()
        // 优先去括号（HRI 格式），再判 FNC1
        val s = raw.replace("(", "").replace(")", "")
        val hasFnc1 = s.indexOf(fnc1) >= 0
        val digitsOnly = s.replace(fnc1.toString(), "")

        // 清晰带标记的 GS1：直接按 AI 解析
        if (hasFnc1 || (isGs1 && raw.contains("("))) {
            return parseStructured(s, fnc1)
        }

        // 纯数字串：按语义判定（核心改进点）
        if (digitsOnly.matches(Regex("^\\d+$"))) {
            val digits = digitsOnly
            val fields = when {
                digits.length == 14 -> {
                    // 14 位：UDI/GTIN。但若缓冲已有 UDI，则新扫到的 14 位数字极可能是别的码（如序列号），判为未知让用户确认
                    if (alreadyHasUdi) listOf(Field(FieldType.UNKNOWN, null, digits))
                    else listOf(Field(FieldType.UDI, "01", digits))
                }
                digits.length == 6 && looksLikeDate(digits) -> {
                    // 6 位且像日期：优先效期(17)，UI 也可让用户改
                    listOf(Field(FieldType.EXPIRY, "17", digits))
                }
                digits.length in 8..20 -> listOf(Field(FieldType.UNKNOWN, null, digits))
                else -> listOf(Field(FieldType.UNKNOWN,  null, digits))
            }
            return Gs1Result(fields, raw)
        }

        // 含字母数字但没有 GS1 标记（如 LOT123、TEXT 等）：当作批号/未知
        if (!hasFnc1 && !isGs1) {
            return Gs1Result(listOf(Field(FieldType.UNKNOWN, null, raw)), raw)
        }

        // 其它带标记但解析不出：尽力顺序切分
        return parseStructured(s, fnc1)
    }

    private fun parseStructured(s: String, fnc1: Char): Gs1Result {
        val fields = mutableListOf<Field>()
        var i = 0
        val n = s.length
        var scannedAny = false
        while (i < n) {
            if (s[i] == fnc1) { i++; continue }
            val ai = when {
                i + 3 <= n && isKnownAi(s.substring(i, i + 3)) -> s.substring(i, i + 3)
                i + 2 <= n && isKnownAi(s.substring(i, i + 2)) -> s.substring(i, i + 2)
                else -> null
            }
            if (ai == null) {
                if (!scannedAny) return Gs1Result(emptyList(), s)
                break
            }
            scannedAny = true
            i += ai.length
            val value = if (FIXED.containsKey(ai)) {
                val len = FIXED[ai]!!
                val v = s.substring(i, minOf(i + len, n))
                i += len
                v
            } else {
                val start = i
                while (i < n && s[i] != fnc1) {
                    if (i + 3 <= n && isKnownAi(s.substring(i, i + 3))) break
                    if (i + 2 <= n && isKnownAi(s.substring(i, i + 2))) break
                    i++
                }
                s.substring(start, i)
            }
            if (value.isEmpty()) continue
            val type = when (ai) {
                "01", "02" -> FieldType.UDI
                "10" -> FieldType.BATCH
                "17" -> FieldType.EXPIRY
                "11" -> FieldType.PROD_DATE
                "21", "91" -> FieldType.SERIAL
                else -> FieldType.UNKNOWN
            }
            fields.add(Field(type, ai, value))
        }
        return Gs1Result(fields, s)
    }

    private fun looksLikeDate(yyMMdd: String): Boolean {
        if (yyMMdd.length != 6) return false
        val yy = yyMMdd.substring(0, 2).toIntOrNull() ?: return false
        val mm = yyMMdd.substring(2, 4).toIntOrNull() ?: return false
        val dd = yyMMdd.substring(4, 6).toIntOrNull() ?: return false
        return yy in 0..99 && mm in 1..12 && dd in 1..31
    }

    /** 把 YYMMDD 转成 YYYY-MM-DD 便于展示；非 6 位原样返回。 */
    fun formatDateYYMMDD(yyMMdd: String?): String? {
        if (yyMMdd == null || yyMMdd.length != 6) return yyMMdd
        return try {
            val d = SimpleDateFormat("yyMMdd", Locale.US).parse(yyMMdd)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d!!)
        } catch (e: Exception) {
            yyMMdd
        }
    }
}
