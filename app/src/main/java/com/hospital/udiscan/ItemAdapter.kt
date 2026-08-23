package com.hospital.udiscan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hospital.udiscan.databinding.ItemScanBinding

class ItemAdapter(
    private val items: MutableList<ScanItem>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    class VH(val b: ItemScanBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.b.tvName.text = it.displayName()
        holder.b.tvDetail.text = it.detailLine()
        holder.b.tvMeta.text = it.metaLine()

        val (txt, color) = when (it.nmpaState) {
            "ok" -> "已查" to R.color.ok
            "pending" -> "待核对" to R.color.pending
            "skip" -> "无记录" to R.color.skip
            "err" -> "查询失败" to R.color.skip
            else -> "" to R.color.text_sub
        }
        holder.b.tvState.text = txt
        holder.b.tvState.setTextColor(holder.b.root.context.getColor(color))
        holder.b.btnDel.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount(): Int = items.size
}
