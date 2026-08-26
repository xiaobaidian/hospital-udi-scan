package com.hospital.udiscan

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * GS1 应用标识符解析 + 字段语义判定（v2：前缀扫描 + 位置切分引擎）。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 为什么重写：
 *   旧版 parseStructured 假设输入带「括号 (01)…」或「FNC1(ASCII 29) 分隔符」。
 *   但实测中，zxing 在多机型把 FNC1 剥离后，扫到的串退化成
 *   「01」+ 14位UDI + 「10」+ 批号 + 「17」+ 6位日期 这种**纯前缀拼接**、
 *   且**无任何分隔符**的格式。此时旧逻辑检测不到 FNC1，会误入「纯数字判定」
 *   分支，把多字段串当成一个 GTIN + 垃圾，批号/效期全丢 —— 这正是「扫出来对不上」主因。
 *
 * 新引擎统一思路（四种输入一份代码覆盖）：
 *   1) 括号 HRI：        (01)0694...(17)250631(10)LOT123
 *   2) FNC1 分隔：       01 0694... <GS> 17 250631 <GS> 10 LOT123
 *   3) 纯前缀拼接(无分隔)：01 0694... 10 LOT123 17 250631   ← 最常见、重点
 *   4) 裸串(单码无 AI)：  06949450446782 / LOT123 / 250631
 *
 * 步骤：归一化 → AI 位置扫描 → 按位置定长/变长切值 → 歧义兜底。
 * ───────────────────────────────────────────────────────────────────────────
 */
object Gs1Parser {

    /** 字段语义类型，供 UI 显示与合并。 */
    enum class FieldType {
        UDI,        // (01)/(02) 医疗器械唯一标识 / GTIN
        BATCH,      // (10) 批号
        EXPIRY,     // (17) 有效期至
        PROD_DATE,  // (11) 生产日期
        SERIAL,     // (21) 或 (91) 序列号
        UNKNOWN     // 无法判定，交由用户选择
    }

    /** 字段来源：UI 可据此展示「识别靠什么」并标记置信度。 */
    enum class Source {
        AI_PREFIX,   // 由 AI 前缀(括号/FNC1/裸前缀)明确识别
        HEURISTIC,   // 歧义兜底启发式判定
        UNCERTAIN    // 完全无法判定，等用户指定
    }

    // ── 已知 AI（我们关心的集合，精简版）──
    // 只保留医疗器械盘点场景真正关心、且不与他人裸数字串冲突的核心 AI。
    // 移除 250/12/13/15/16/20/240/241/242/400/401/422/710/8004：它们在真实场景极少用，
    // 却会跟裸 6 位日期(250631)、裸 12/8 位 UPC 冲突，导致误切。
    // 定长 AI：AI -> 固定字符数
    private val FIXED = mapOf(
        "01" to 14, "02" to 14,
        "11" to 6, "17" to 6
    )

    // 变长 AI（遇到下一个 AI 或串尾即结束）
    private val VAR = setOf(
        "10", "21", "91"
    )

    // 全部已知 AI（含定长+变长），用于「位置扫描」时识别 AI 起点
    private val ALL_AI = (FIXED.keys + VAR).toSet()

    /** 字段语义 → 类型 */
    private fun typeOf(ai: String): FieldType = when (ai) {
        "01", "02" -> FieldType.UDI
        "10" -> FieldType.BATCH
        "17" -> FieldType.EXPIRY
        "11" -> FieldType.PROD_DATE
        "21", "91" -> FieldType.SERIAL
        else -> FieldType.UNKNOWN
    }

    data class Field(
        val type: FieldType,
        val ai: String?,          // 识别到的 AI，如 "01"；歧义兜底时为 null
        val value: String,
        val source: Source = Source.AI_PREFIX
    )

    data class Gs1Result(
        val fields: List<Field>,  // 保留顺序，方便 UI 展示「本次扫到了什么」
        val raw: String
    )

    /**
     * 解析入口。
     * @param raw 原始扫码字符串
     * @param alreadyHasUdi 当前缓冲是否已存在 UDI——用于裸串/歧义判定（已有 UDI 时新的 14 位数字优先视为批号/序列号）
     */
    fun parse(raw: String, alreadyHasUdi: Boolean = false): Gs1Result {
        if (raw.isEmpty()) return Gs1Result(emptyList(), raw)

        // 1) 归一化：剥括号；FNC1/GS(ASCII 29) 直接删除（不留空格，避免定长值误吞分隔符）
        //    二维码常把「两行条形码」拼接成一个串，行间用 \n 或 \r 分隔 —— 统一转成空格，
        //    这样后续按位置扫描时两行内容被正确切分为多个字段。
        val fnc1 = 29.toChar()
        val s = raw
            .replace("(", "")
            .replace(")", "")
            .replace(fnc1.toString(), "")
            .replace("\n", " ")
            .replace("\r", " ")
        val clean = s

        // 2) 若整串「不以已知 AI 开头」（如裸日期 250631、裸非 GTIN 14 位），
        //    直接走兜底启发式，避免 AI 扫描把纯数字串切碎。
        val stripped = clean.replace(" ", "")
        if (stripped.isEmpty() || !startsWithAi(stripped)) {
            val fb = fallbackHeuristic(stripped, alreadyHasUdi)
            if (fb != null) return Gs1Result(listOf(fb), raw)
            return Gs1Result(listOf(Field(FieldType.UNKNOWN, null, clean.trim(), Source.UNCERTAIN)), raw)
        }

        // 3) AI 位置扫描 + 切分（主路径，覆盖括号/FNC1/纯前缀拼接）
        val fields = scanByAiPrefix(clean)
        if (fields.isNotEmpty()) {
            return Gs1Result(fields, raw)
        }

        // 4) 主路径失败兜底
        val fb = fallbackHeuristic(stripped, alreadyHasUdi)
        if (fb != null) return Gs1Result(listOf(fb), raw)
        return Gs1Result(listOf(Field(FieldType.UNKNOWN, null, clean.trim(), Source.UNCERTAIN)), raw)
    }

    /** 判断串首是否是已知 AI（3 位优先，再 2 位）。 */
    private fun startsWithAi(s: String): Boolean {
        if (s.length >= 3 && s.substring(0, 3).all { it.isDigit() } && ALL_AI.contains(s.substring(0, 3))) return true
        if (s.length >= 2 && s.substring(0, 2).all { it.isDigit() } && ALL_AI.contains(s.substring(0, 2))) return true
        return false
    }

    /**
     * 判断位置 [pos] 是否是「真 AI 起点」：不仅是数字串在 ALL_AI，
     * 定长 AI 还要求其后跟的字符数达到定长（避免 2024 里的 02 被误判为 AI）。
     *
     * 本函数只服务「变长字段找切分边界」：日期型 AI(11/17) 还要求其后 6 位
     * 是真日期 —— 否则纯数字序列号/批号内部的巧合子串（如 30112501… 中的 "11"）
     * 会被误当成边界，把序列号切碎。
     */
    private fun isAiBoundary(s: String, pos: Int): Boolean {
        if (pos + 3 <= s.length) {
            val a3 = s.substring(pos, pos + 3)
            if (a3.all { it.isDigit() } && ALL_AI.contains(a3)) {
                if (FIXED.containsKey(a3)) return pos + 3 + FIXED[a3]!! <= s.length
                return true
            }
        }
        if (pos + 2 <= s.length) {
            val a2 = s.substring(pos, pos + 2)
            if (a2.all { it.isDigit() } && ALL_AI.contains(a2)) {
                if (FIXED.containsKey(a2)) {
                    if (pos + 2 + FIXED[a2]!! > s.length) return false
                    // 日期型 AI 作为切分边界：值必须像真日期才可信
                    if ((a2 == "11" || a2 == "17") &&
                        !looksLikeDate(s.substring(pos + 2, pos + 2 + FIXED[a2]!!))
                    ) return false
                    return true
                }
                return true
            }
        }
        return false
    }

    /** 返回 pos 处的 AI（3 位优先，再 2 位），非真边界返回 null。 */
    private fun aiAt(s: String, pos: Int): String? {
        if (pos + 3 <= s.length) {
            val a3 = s.substring(pos, pos + 3)
            if (a3.all { it.isDigit() } && ALL_AI.contains(a3)) {
                if (FIXED.containsKey(a3) && pos + 3 + FIXED[a3]!! > s.length) return null
                return a3
            }
        }
        if (pos + 2 <= s.length) {
            val a2 = s.substring(pos, pos + 2)
            if (a2.all { it.isDigit() } && ALL_AI.contains(a2)) {
                if (FIXED.containsKey(a2) && pos + 2 + FIXED[a2]!! > s.length) return null
                return a2
            }
        }
        return null
    }

    /**
     * 主路径：扫描整串所有「AI 前缀起点」，按位置切值。
     * AI 只在串首或上一字段值结束处出现；变长值吃到下一个真 AI 边界或串尾。
     */
    private fun scanByAiPrefix(clean: String): List<Field> {
        val s = clean
        val n = s.length
        val fields = mutableListOf<Field>()
        var i = 0
        while (i < n) {
            if (s[i] == ' ' || s[i].isWhitespace()) { i++; continue }
            val ai = aiAt(s, i)
            if (ai == null) {
                if (fields.isEmpty()) return emptyList()  // 整串无 AI 前缀 → 兜底
                break  // 已有字段却遇非 AI 字符：截断保护
            }
            i += ai.length
            val value: String
            if (FIXED.containsKey(ai)) {
                val len = FIXED[ai]!!
                value = s.substring(i, minOf(i + len, n)).trim()
                i += len
                // 日期型定长 AI(11/17)：后面必须是 6 位「能解析成日期」的数字才算数。
                // 不是真日期（标签错印 / AI 出现在不该出现的位置）→ 该段降级为 UNKNOWN，
                // 不污染效期/生产日字段。下游可让用户手动指定。
                if ((ai == "11" || ai == "17") && !looksLikeDate(value)) {
                    fields.add(Field(FieldType.UNKNOWN, ai, value, Source.UNCERTAIN))
                    continue
                }
            } else {
                // 变长：吃到「下一个真 AI 边界」或分隔符或串尾。
                // 序列号(21/91)按 GS1 惯例位于串尾，且其值可能是任意数字组合
                // （如 30 开头的长串，内部极易撞上 01/10/11/17/21 等伪前缀），
                // 故序列号字段不做 AI 边界扫描，只按空格（两行条码换行处）断，
                // 其余一律吃到串尾 —— 保证结尾的序列号完整不被切碎。
                val serialTail = (ai == "21" || ai == "91")
                val start = i
                while (i < n) {
                    if (s[i] == ' ') break
                    if (!serialTail && isAiBoundary(s, i)) break
                    i++
                }
                value = s.substring(start, i)
            }
            if (value.isEmpty()) continue
            fields.add(Field(typeOf(ai), ai, value, Source.AI_PREFIX))
        }
        return fields
    }

    /**
     * 兜底：无 AI 前缀的裸串。
     * @return 判定出的字段，或 null（表示完全无法判定，交给上层 UNKNOWN）
     */
    private fun fallbackHeuristic(digits: String, alreadyHasUdi: Boolean): Field? {
        // 裸串以序列号 AI 开头（21/91）：后面整段即序列号（序列号常在末尾，不会误判）
        if (digits.startsWith("21") || digits.startsWith("91")) {
            val rest = digits.substring(2)
            if (rest.isNotEmpty()) return Field(FieldType.SERIAL, digits.substring(0, 2), rest, Source.AI_PREFIX)
        }
        // 裸串以日期 AI 开头（11/17）：必须后接 6 位真日期才算数
        if (digits.startsWith("11") || digits.startsWith("17")) {
            val rest = digits.substring(2)
            if (rest.length == 6 && looksLikeDate(rest)) {
                return Field(typeOf(digits.substring(0, 2)), digits.substring(0, 2), rest, Source.AI_PREFIX)
            }
            return Field(FieldType.UNKNOWN, digits.substring(0, 2), rest, Source.UNCERTAIN)
        }
        return when {
            // 14 位：仅过 GTIN-14 校验位才敢当 UDI；否则结合上下文降级
            digits.length == 14 -> {
                if (isValidGtin14(digits)) {
                    Field(FieldType.UDI, "01", digits, Source.AI_PREFIX)
                } else if (alreadyHasUdi) {
                    Field(FieldType.BATCH, "10", digits, Source.HEURISTIC)
                } else {
                    Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
                }
            }
            // 13 位 EAN-13：补校验位成 GTIN-14 验证，过则视为 UDI
            digits.length == 13 -> {
                val g14 = "0$digits"
                if (isValidGtin14(g14)) {
                    Field(FieldType.UDI, "01", g14, Source.AI_PREFIX)
                } else if (alreadyHasUdi) {
                    Field(FieldType.BATCH, "10", g14, Source.HEURISTIC)
                } else {
                    Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
                }
            }
            // 12 / 8 位 UPC/EAN → 规范补位成 GTIN-14（仅纯数字，含字母的串不走此分支）
            digits.length == 12 && digits.all { it.isDigit() } -> {
                val g14 = "00$digits"
                if (isValidGtin14(g14)) Field(FieldType.UDI, "01", g14, Source.AI_PREFIX)
                else if (alreadyHasUdi) Field(FieldType.BATCH, "10", digits, Source.HEURISTIC)
                else Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
            }
            digits.length == 8 && digits.all { it.isDigit() } -> {
                val g14 = "000000$digits"
                if (isValidGtin14(g14)) Field(FieldType.UDI, "01", g14, Source.AI_PREFIX)
                else if (alreadyHasUdi) Field(FieldType.BATCH, "10", digits, Source.HEURISTIC)
                else Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
            }
            // 6 位且像日期：效期候选（生产/效期歧义交由 UI 或上下文，这里默认效期）
            digits.length == 6 && looksLikeDate(digits) -> {
                Field(FieldType.EXPIRY, "17", digits, Source.HEURISTIC)
            }
            // 含字母数字的裸串（如 SN12345、LOT2024-A）：不可能是纯数字 GTIN，
            // 已存在 UDI 时基本可确定是序列号。必须放在纯数字分支之前，否则会被 7~20 位误判为 BATCH。
            digits.any { it.isLetter() } && digits.length in 3..30 -> {
                if (alreadyHasUdi) Field(FieldType.SERIAL, "21", digits, Source.HEURISTIC)
                else Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
            }
            // 7~20 位纯数字且无 UDI：可能序列号/物流码，交给用户指定
            digits.length in 7..20 -> {
                if (alreadyHasUdi) Field(FieldType.SERIAL, "21", digits, Source.HEURISTIC)
                else Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
            }
            else -> Field(FieldType.UNKNOWN, null, digits, Source.UNCERTAIN)
        }
    }

    /**
     * GTIN-14 / EAN 校验位验证（GS1 标准 mod10，奇数位乘 3）。
     * 用于在没有 GS1 括号/FNC1 标记时，判断一段纯数字到底是不是合法的 UDI/GTIN，
     * 从而大幅降低「随机数字串被误判为 UDI」的错位。
     */
    fun isValidGtin14(gtin: String): Boolean {
        if (gtin.length != 14 || !gtin.all { it.isDigit() }) return false
        val sum = gtin.take(13).mapIndexed { i, c ->
            val d = c.digitToInt()
            if (i % 2 == 0) d * 3 else d
        }.sum()
        val check = (10 - (sum % 10)) % 10
        return check == gtin.last().digitToInt()
    }

    private fun looksLikeDate(yyMMdd: String): Boolean {
        if (yyMMdd.length != 6) return false
        val yy = yyMMdd.substring(0, 2).toIntOrNull() ?: return false
        val mm = yyMMdd.substring(2, 4).toIntOrNull() ?: return false
        val dd = yyMMdd.substring(4, 6).toIntOrNull() ?: return false
        // 年份收紧到 25..35（2025–2035）：医疗器械标签的实际年份窗口，
        // 防止巧合的 6 位数字（如序列号片段）被误判成日期。
        return yy in 25..35 && mm in 1..12 && dd in 1..31
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
