package com.hospital.udiscan

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * GS1 应用标识符解析。
 * 支持两种输入：
 *  1) 括号化 HRI 字符串，如 (01)06949450446782(17)250631(10)LOT123
 *  2) FNC1(ASCII 29) 分隔的原始串，如 01 06949450446782 <GS> 17 250631 <GS> 10 LOT123
 * 当 zxing 标记 isGs1=true 但剥掉了 FNC1 时，按 AI 起始做尽力顺序切分（变长 AI 可能误切，风险可控）。
 *
 * 重点：部分厂家序列号用 (91) 而非标准 (21)，二者都按序列号处理。
 */
object Gs1Parser {

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

    data class Gs1Result(
        val fields: Map<String, String>,
        val raw: String,
        val isGs1: Boolean
    )

    fun parse(raw: String, isGs1: Boolean = false): Gs1Result {
        val fields = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return Gs1Result(fields, raw, false)

        // 去括号（HRI 格式）
        val s = raw.replace("(", "").replace(")", "")
        val fnc1 = 29.toChar()
        val hasFnc1 = s.indexOf(fnc1) >= 0
        val digitsOnly = s.replace(fnc1.toString(), "")

        // 纯数字 12~14 位：当作 GTIN / (01)
        if (!hasFnc1 && !isGs1 && digitsOnly.matches(Regex("^\\d{12,14}$"))) {
            fields["01"] = digitsOnly
            return Gs1Result(fields, raw, true)
        }

        // 既没有 FNC1 也不是 GS1 标记：无法可靠解析，按纯文本返回（调用方保留 raw）
        if (!hasFnc1 && !isGs1) {
            return Gs1Result(fields, raw, false)
        }

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
                // 无法识别的片段：若尚未扫到任何 AI，多半是纯文本
                if (!scannedAny) return Gs1Result(emptyMap(), raw, false)
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
                // 变长：读到下一个 FNC1，或下一个已知 AI 起始，或结尾
                val start = i
                while (i < n && s[i] != fnc1) {
                    if (i + 3 <= n && isKnownAi(s.substring(i, i + 3))) break
                    if (i + 2 <= n && isKnownAi(s.substring(i, i + 2))) break
                    i++
                }
                s.substring(start, i)
            }
            if (value.isNotEmpty()) fields[ai] = value
        }
        return Gs1Result(fields, raw, scannedAny)
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
