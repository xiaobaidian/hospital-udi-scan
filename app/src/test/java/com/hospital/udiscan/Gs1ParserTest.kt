package com.hospital.udiscan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gs1Parser 单元测试（随 CI `./gradlew test` 回归）。
 * 覆盖：括号 HRI、纯前缀拼接(无分隔)、FNC1 分隔、裸 GTIN、裸非 GTIN、91 序列号、6位日期、UDI+批号拼接、UPC 补位。
 */
class Gs1ParserTest {

    private fun types(raw: String, alreadyHasUdi: Boolean = false): List<Pair<Gs1Parser.FieldType, String?>> {
        return Gs1Parser.parse(raw, alreadyHasUdi).fields.map { it.type to it.value }
    }

    @Test
    fun `括号 HRI 完整`() {
        val r = types("(01)06949450446782(17)250631(10)LOT123")
        assertEquals(listOf(
            Gs1Parser.FieldType.UDI to "06949450446782",
            Gs1Parser.FieldType.EXPIRY to "250631",
            Gs1Parser.FieldType.BATCH to "LOT123"
        ), r)
    }

    @Test
    fun `纯前缀拼接无分隔 最常见`() {
        val r = types("010694945044678210LOT12317250631")
        assertEquals(listOf(
            Gs1Parser.FieldType.UDI to "06949450446782",
            Gs1Parser.FieldType.BATCH to "LOT123",
            Gs1Parser.FieldType.EXPIRY to "250631"
        ), r)
    }

    @Test
    fun `FNC1 分隔`() {
        val fnc1 = "\u001d"
        val r = types("01${fnc1}06949450446782${fnc1}17${fnc1}250631${fnc1}10${fnc1}LOT123")
        assertEquals(listOf(
            Gs1Parser.FieldType.UDI to "06949450446782",
            Gs1Parser.FieldType.EXPIRY to "250631",
            Gs1Parser.FieldType.BATCH to "LOT123"
        ), r)
    }

    @Test
    fun `裸 14 位 GTIN`() {
        val r = types("06949450446782")
        assertEquals(listOf(Gs1Parser.FieldType.UDI to "06949450446782"), r)
    }

    @Test
    fun `裸 14 位非 GTIN 无 UDI 应 UNKNOWN`() {
        val r = types("12345678901234")
        assertEquals(listOf(Gs1Parser.FieldType.UNKNOWN to "12345678901234"), r)
    }

    @Test
    fun `裸 14 位非 GTIN 已有 UDI 应 BATCH`() {
        val r = types("12345678901234", alreadyHasUdi = true)
        assertEquals(listOf(Gs1Parser.FieldType.BATCH to "12345678901234"), r)
    }

    @Test
    fun `91 序列号 括号`() {
        val r = types("(01)06949450446782(91)SN998877")
        assertEquals(listOf(
            Gs1Parser.FieldType.UDI to "06949450446782",
            Gs1Parser.FieldType.SERIAL to "SN998877"
        ), r)
    }

    @Test
    fun `纯前缀含 91`() {
        val r = types("010694945044678291SN998877")
        assertEquals(listOf(
            Gs1Parser.FieldType.UDI to "06949450446782",
            Gs1Parser.FieldType.SERIAL to "SN998877"
        ), r)
    }

    @Test
    fun `仅效期 6 位`() {
        val r = types("250631")
        assertEquals(listOf(Gs1Parser.FieldType.EXPIRY to "250631"), r)
    }

    @Test
    fun `UDI 加 批号 两行拼接`() {
        val r = types("010694945044678210ABC2024")
        assertEquals(listOf(
            Gs1Parser.FieldType.UDI to "06949450446782",
            Gs1Parser.FieldType.BATCH to "ABC2024"
        ), r)
    }

    @Test
    fun `8 位 EAN 补位`() {
        val r = types("96385074")
        assertEquals(listOf(Gs1Parser.FieldType.UDI to "00000096385074"), r)
    }
}
