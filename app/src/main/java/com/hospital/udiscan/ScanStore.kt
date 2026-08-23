package com.hospital.udiscan

import java.util.UUID

/**
 * 跨页面共享的已录入清单（内存态，生命周期等同本次 App 进程）。
 * MainActivity 负责写入（扫码加入），ManageActivity 负责导出 / 清空。
 */
object ScanStore {
    val items: MutableList<ScanItem> = mutableListOf()
    val listeners: MutableList<() -> Unit> = mutableListOf()

    fun add(item: ScanItem) {
        items.add(0, item)
        notifyListeners()
    }

    fun removeAt(pos: Int) {
        if (pos in 0 until items.size) {
            items.removeAt(pos)
            notifyListeners()
        }
    }

    fun clear() {
        items.clear()
        notifyListeners()
    }

    fun notifyListeners() {
        for (l in listeners) l()
    }
}
