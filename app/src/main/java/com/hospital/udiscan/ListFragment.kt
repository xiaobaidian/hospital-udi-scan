package com.hospital.udiscan

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 清单页（ViewPager2 第 1 页）：展示已录入的扫码记录。
 * 观察 ScanViewModel.listVersion 实时刷新；删除按 id、编辑写本地字典 override。
 * 顶部「管理」进入 ManageActivity（导出 / 清空 / 字典）。
 */
class ListFragment : Fragment() {

    private lateinit var vm: ScanViewModel
    private lateinit var listItems: RecyclerView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnManage: Button
    private lateinit var btnClearList: Button
    private lateinit var adapter: ItemAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]

        listItems = view.findViewById(R.id.list_items)
        tvCount = view.findViewById(R.id.tv_count)
        tvEmpty = view.findViewById(R.id.tv_empty)
        btnManage = view.findViewById(R.id.btn_manage)
        btnClearList = view.findViewById(R.id.btn_clear_list)

        adapter = ItemAdapter(vm.items,
            onDelete = { id -> deleteItem(id) },
            onEdit = { id -> editItem(id) }
        )
        listItems.layoutManager = LinearLayoutManager(requireContext())
        listItems.adapter = adapter

        btnManage.setOnClickListener {
            startActivity(Intent(requireContext(), ManageActivity::class.java))
        }

        btnClearList.setOnClickListener { clearList() }

        // 实时刷新：清单变更 / 缓冲变更（加入后）都刷新
        vm.listVersion.observe(viewLifecycleOwner) { refresh() }
        refresh()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = vm.items.size
        tvCount.text = "共 $n 条"
        tvEmpty.visibility = if (n == 0) View.VISIBLE else View.GONE
        listItems.visibility = if (n == 0) View.GONE else View.VISIBLE
    }

    private fun deleteItem(id: String) {
        vm.removeItemById(id)
        toast(R.string.toast_deleted)
    }

    private fun clearList() {
        if (vm.items.isEmpty()) { toast(R.string.toast_dict_empty); return }
        AlertDialog.Builder(requireContext())
            .setTitle("清空清单")
            .setMessage("确定移除本次已录入的全部条目？此操作不可撤销。")
            .setPositiveButton("清空") { _, _ ->
                vm.items.clear()
                ScanStore.clear()
                toast(R.string.toast_clear_done)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editItem(id: String) {
        val it = vm.items.firstOrNull { x -> x.id == id } ?: return
        val udi = it.udiDi ?: run { toast(R.string.toast_no_udi); return }
        val existing = NmpaCache.getOverride(udi) ?: NmpaCache.get(udi)
        showEditDialog(
            udi,
            defName = existing?.productName ?: it.productName,
            defSpec = existing?.specification ?: it.specification,
            defCompany = existing?.companyName ?: it.companyName,
            defBatch = it.batch,
            defExpiry = it.expiry,
            defProd = it.production,
            defSerial = it.serial,
            defSerialAi = it.serialAi,
            defQty = it.quantity
        ) { name, spec, company, batch, expiry, prod, serial, serialAi, qty ->
            // 1) 字典字段（名称/型号/厂家）→ 仅当至少一项非空才写自定义字典 + 同步同 UDI
            if (!name.isNullOrEmpty() || !spec.isNullOrEmpty() || !company.isNullOrEmpty()) {
                NmpaCache.putOverride(udi, name, spec, company)
                val n = vm.updateAllByUdi(udi, name, spec, company)
                if (n > 0) toast(getString(R.string.toast_saved_all, n))
            }
            // 2) 本条字段（批号/效期/生产/序列/数量）→ 仅更新本条，不写字典
            it.updateListFields(batch, expiry, prod, serial, serialAi, qty)
            vm.refreshItem(it)
            toast(R.string.toast_saved)
        }
    }

    private fun showEditDialog(
        udi: String,
        defName: String?, defSpec: String?, defCompany: String?,
        defBatch: String?, defExpiry: String?, defProd: String?,
        defSerial: String?, defSerialAi: String?, defQty: Int,
        onSaved: (String?, String?, String?, String?, String?, String?, String?, String?, Int?) -> Unit
    ) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_product, null)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_name)
        val etSpec = view.findViewById<android.widget.EditText>(R.id.et_spec)
        val etCompany = view.findViewById<android.widget.EditText>(R.id.et_company)
        val etBatch = view.findViewById<android.widget.EditText>(R.id.et_batch)
        val etExpiry = view.findViewById<android.widget.EditText>(R.id.et_expiry)
        val etProd = view.findViewById<android.widget.EditText>(R.id.et_prod)
        val etSerial = view.findViewById<android.widget.EditText>(R.id.et_serial)
        val etQtyItem = view.findViewById<android.widget.EditText>(R.id.et_qty_item)
        etName.setText(defName ?: "")
        etSpec.setText(defSpec ?: "")
        etCompany.setText(defCompany ?: "")
        etBatch.setText(defBatch ?: "")
        etExpiry.setText(defExpiry ?: "")
        etProd.setText(defProd ?: "")
        etSerial.setText(defSerial ?: "")
        etQtyItem.setText(defQty.toString())
        AlertDialog.Builder(ctx)
            .setTitle("编辑条目")
            .setMessage("UDI(01): $udi")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().let { if (it.isEmpty()) null else it }
                val spec = etSpec.text.toString().trim().let { if (it.isEmpty()) null else it }
                val company = etCompany.text.toString().trim().let { if (it.isEmpty()) null else it }
                val batch = etBatch.text.toString().trim().let { if (it.isEmpty()) null else it }
                val expiry = etExpiry.text.toString().trim().let { if (it.isEmpty()) null else it }
                val prod = etProd.text.toString().trim().let { if (it.isEmpty()) null else it }
                val serial = etSerial.text.toString().trim().let { if (it.isEmpty()) null else it }
                val serialAi = if (serial.isNullOrEmpty()) null else (defSerialAi ?: "21")
                val qty = etQtyItem.text.toString().toIntOrNull()
                onSaved(name, spec, company, batch, expiry, prod, serial, serialAi, qty)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
