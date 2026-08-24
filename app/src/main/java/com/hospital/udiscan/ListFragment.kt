package com.hospital.udiscan

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.BuildConfig

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

        adapter = ItemAdapter(vm.items,
            onDelete = { id -> deleteItem(id) },
            onEdit = { id -> editItem(id) }
        )
        listItems.layoutManager = LinearLayoutManager(requireContext())
        listItems.adapter = adapter

        btnManage.setOnClickListener {
            startActivity(Intent(requireContext(), ManageActivity::class.java))
        }

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

    private fun editItem(id: String) {
        val it = vm.items.firstOrNull { x -> x.id == id } ?: return
        val udi = it.udiDi ?: run { toast(R.string.toast_no_udi); return }
        val existing = NmpaCache.getOverride(udi) ?: NmpaCache.get(udi)
        showEditDialog(udi, existing?.productName ?: it.productName,
            existing?.specification ?: it.specification,
            existing?.companyName ?: it.companyName) { name, spec, company ->
            // 写 override 后就地刷新 item
            it.productName = name ?: it.productName
            it.specification = spec ?: it.specification
            it.companyName = company ?: it.companyName
            it.nmpaState = "local"
            vm.refreshItem(it)
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
}
