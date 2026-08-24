package com.hospital.udiscan

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.UUID

/**
 * Activity 作用域的共享状态中枢：
 * - 扫码缓冲（当前待录入，跨帧/多码累积合并）放在这里，滑到清单页再滑回不丢失；
 * - 已录入清单直接委托 ScanStore.items（内存态），并通过 LiveData 通知清单页刷新。
 *
 * 两个 Fragment（ScanFragment / ListFragment）共享同一个实例。
 */
class ScanViewModel : ViewModel() {

    // —— 当前缓冲（待录入）——
    // LinkedHashSet：既去重（同串不重复处理）又保留扫码顺序，供「解析前原始串」卡片按序展示。
    val scannedRaws = LinkedHashSet<String>()
    var bufUdi: String? = null
    var bufBatch: String? = null
    var bufExpiry: String? = null
    var bufProduction: String? = null
    var bufSerial: String? = null
    var bufSerialAi: String? = null
    var bufProduct: String? = null
    var bufSpec: String? = null
    var bufCompany: String? = null
    var bufNmpaState: String = "none"
    var bufQty: Int = 1
    var queriedUdi: String? = null
    // 本次扫描来源：barcode=一维码(Code128 等) / qr=二维码(QR/DataMatrix)
    var bufSource: String = "barcode"
    // 待确认（未知段）：缓冲还没有 UDI 时，暂存未归类的纯数字/文本
    var bufPendingUnknown: List<String> = emptyList()

    // —— 已录入清单（委托 ScanStore）——
    val items: MutableList<ScanItem> get() = ScanStore.items

    private val _listVersion = MutableLiveData(0L)
    /** 清单变更版本号，清单页观察它即可刷新。 */
    val listVersion: LiveData<Long> get() = _listVersion

    private val _bufferVersion = MutableLiveData(0L)
    /** 缓冲变更版本号，扫码页观察它刷新预览卡/缓冲卡。 */
    val bufferVersion: LiveData<Long> get() = _bufferVersion

    private fun bumpList() {
        _listVersion.value = (_listVersion.value ?: 0) + 1
    }

    private fun bumpBuffer() {
        _bufferVersion.value = (_bufferVersion.value ?: 0) + 1
    }

    fun notifyBufferChanged() = bumpBuffer()

    /** 把当前缓冲汇总并加入清单（在列表头部插入），随后清空缓冲。 */
    fun commitBuffer(): ScanItem {
        val item = ScanItem(
            id = UUID.randomUUID().toString(),
            udiDi = bufUdi,
            batch = bufBatch,
            expiry = bufExpiry,
            production = bufProduction,
            serial = bufSerial,
            serialAi = bufSerialAi,
            productName = bufProduct,
            specification = bufSpec,
            companyName = bufCompany,
            nmpaState = bufNmpaState,
            quantity = bufQty,
            raw = buildRaw(),
            scannedAt = ScanItem.nowStamp()
        )
        ScanStore.add(item)   // 内部已 notifyListeners
        bumpList()
        clearBuffer()
        return item
    }

    /** 删除指定 id 的清单条目（按 id 删，避免位置错位）。 */
    fun removeItemById(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        items.removeAt(idx)
        bumpList()
    }

    /** 就地刷新某清单条目（编辑后）。 */
    fun refreshItem(item: ScanItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) bumpList()
    }

    /**
     * 按 UDI 批量同步：把同一 UDI-DI 的所有已录入条目，统一更新为新的名称/型号/厂家。
     * 用于「改了某个 UDI 的型号/名称，凡是同 UDI 的条目都要跟着改」的需求。
     * 返回被更新的条目数（含传入的 it 自身）。
     */
    fun updateAllByUdi(udi: String, name: String?, spec: String?, company: String?): Int {
        if (udi.isBlank()) return 0
        var changed = 0
        for (it in items) {
            if (it.udiDi == udi) {
                it.productName = name ?: it.productName
                it.specification = spec ?: it.specification
                it.companyName = company ?: it.companyName
                it.nmpaState = "local"
                changed++
            }
        }
        if (changed > 0) bumpList()
        return changed
    }

    fun clearBuffer() {
        scannedRaws.clear()
        bufUdi = null; bufBatch = null; bufExpiry = null; bufProduction = null
        bufSerial = null; bufSerialAi = null
        bufProduct = null; bufSpec = null; bufCompany = null
        bufNmpaState = "none"; bufQty = 1; queriedUdi = null
        bufPendingUnknown = emptyList()
        bufSource = "barcode"
        bumpBuffer()
    }

    /** 汇总当前缓冲的原始扫码内容（含待确认段），用于导出与审计。 */
    private fun buildRaw(): String {
        val parts = mutableListOf<String>()
        bufUdi?.let { parts.add("(01)$it") }
        bufBatch?.let { parts.add("(10)$it") }
        bufExpiry?.let { parts.add("(17)$it") }
        bufProduction?.let { parts.add("(11)$it") }
        bufSerial?.let { parts.add("(${bufSerialAi ?: "21"})$it") }
        parts.addAll(bufPendingUnknown.map { "待确认:$it" })
        if (parts.isEmpty()) parts.addAll(scannedRaws)
        return parts.joinToString(" | ")
    }

    /**
     * 解析前拼接好的原始扫码串（保序），按「含 UDI 的在前、其余在后」排序，
     * 供扫码页「原始串」卡片展示，便于用户核对捕获内容。
     * 返回 Pair(原始串, 是否含 UDI)。
     */
    fun rawDumpSorted(): List<Pair<String, Boolean>> {
        val list = scannedRaws.map { raw ->
            val hasUdi = Gs1Parser.parse(raw, alreadyHasUdi = false).fields
                .any { it.type == Gs1Parser.FieldType.UDI }
            raw to hasUdi
        }
        return list.sortedBy { if (it.second) 0 else 1 }
    }
}
