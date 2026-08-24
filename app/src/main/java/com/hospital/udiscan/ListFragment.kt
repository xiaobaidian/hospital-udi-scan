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
        showEditDialog(udi, existing?.productName ?: it.productName,
            existing?.specification ?: it.specification,
            existing?.companyName ?: it.companyName) { name, spec, company ->
            // 1) 写入用户覆盖字典（落库，跨设备同步用）
            NmpaCache.putOverride(udi, name, spec, company)
            // 2) 同 UDI 的所有已录入条目一并更新（型号/名称/厂家）
            val n = vm.updateAllByUdi(udi, name, spec, company)
            toast(getString(R.string.toast_saved_all, n))
        }
    }

    private fun showEditDialog(
        udi: String, defName: String?, defSpec: String?, defCompany: String?,
        onSaved: (String?, String?, String?) -> Unit
    ) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_product, null)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_name)
        val etSpec = view.findViewById<android.widget.EditText>(R.id.et_spec)
        val etCompany = view.findViewById<android.widget.EditText>(R.id.et_company)
        etName.setText(defName ?: "")
        etSpec.setText(defSpec ?: "")
        etCompany.setText(defCompany ?: "")
        AlertDialog.Builder(ctx)
            .setTitle("编辑产品字典")
            .setMessage("UDI(01): $udi")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().let { if (it.isEmpty()) null else it }
                val spec = etSpec.text.toString().trim().let { if (it.isEmpty()) null else it }
                val company = etCompany.text.toString().trim().let { if (it.isEmpty()) null else it }
                NmpaCache.putOverride(udi, name, spec, company)
                onSaved(name, spec, company)
                toast(R.string.toast_saved)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
