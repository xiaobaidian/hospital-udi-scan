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
    val scannedRaws = mutableSetOf<String>()
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

    fun clearBuffer() {
        scannedRaws.clear()
        bufUdi = null; bufBatch = null; bufExpiry = null; bufProduction = null
        bufSerial = null; bufSerialAi = null
        bufProduct = null; bufSpec = null; bufCompany = null
        bufNmpaState = "none"; bufQty = 1; queriedUdi = null
        bufPendingUnknown = emptyList()
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
}
