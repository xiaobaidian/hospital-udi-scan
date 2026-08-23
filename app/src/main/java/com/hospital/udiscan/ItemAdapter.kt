package com.hospital.udiscan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ItemAdapter(
    private val items: MutableList<ScanItem>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val tvDetail: TextView = itemView.findViewById(R.id.tv_detail)
        val tvMeta: TextView = itemView.findViewById(R.id.tv_meta)
        val tvState: TextView = itemView.findViewById(R.id.tv_state)
        val btnDel: Button = itemView.findViewById(R.id.btn_del)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_scan, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.tvName.text = it.displayName()
        holder.tvDetail.text = it.detailLine()
        holder.tvMeta.text = it.metaLine()

        val (txt, color) = when (it.nmpaState) {
            "ok" -> "已查" to R.color.ok
            "pending" -> "待核对" to R.color.pending
            "skip" -> "无记录" to R.color.skip
            "err" -> "查询失败" to R.color.skip
            else -> "" to R.color.text_sub
        }
        holder.tvState.text = txt
        holder.tvState.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
        holder.btnDel.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount(): Int = items.size
}
