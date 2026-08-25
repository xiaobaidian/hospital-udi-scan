package com.hospital.udiscan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

/**
 * 字典查看 / 编辑：分别列出 NMPA 官方缓存与自定义覆盖字典的全部条目，
 * 支持就地编辑（改名 / 型号 / 厂家）与删除。编辑自定义字典会同步影响扫码命中。
 */
class DictActivity : AppCompatActivity() {

    private lateinit var tabNmpa: Button
    private lateinit var tabCustom: Button
    private lateinit var btnAddCustom: Button
    private lateinit var tvSummary: TextView
    private lateinit var listContainer: LinearLayout
    private var currentTab: String = "nmpa"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dict)

        tabNmpa = findViewById(R.id.tab_nmpa)
        tabCustom = findViewById(R.id.tab_custom)
        btnAddCustom = findViewById(R.id.btn_add_custom)
        tvSummary = findViewById(R.id.tv_summary)
        listContainer = findViewById(R.id.list_container)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        tabNmpa.setOnClickListener { switchTab("nmpa") }
        tabCustom.setOnClickListener { switchTab("custom") }
        btnAddCustom.setOnClickListener { addCustomEntry() }

        switchTab("nmpa")
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        val on = android.graphics.Color.parseColor("#1565C0")
        val off = android.graphics.Color.parseColor("#FFFFFF")
        tabNmpa.setBackgroundColor(if (tab == "nmpa") on else off)
        tabNmpa.setTextColor(if (tab == "nmpa") off else on)
        tabCustom.setBackgroundColor(if (tab == "custom") on else off)
        tabCustom.setTextColor(if (tab == "custom") off else on)
        // 仅自定义字典页显示「新增」入口
        btnAddCustom.visibility = if (tab == "custom") View.VISIBLE else View.GONE
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        if (currentTab == "nmpa") {
            val items = NmpaCache.getAllCache()
            tvSummary.text = "NMPA 官方缓存：${items.size} 条（只读查询结果，删除后下次查询会重新联网）"
            for (it in items) addRow(it.udi, it.name, it.spec, it.company, it.state, false)
        } else {
            val items = NmpaCache.getAllOverrides()
            tvSummary.text = "自定义字典：${items.size} 条（改名 / 手补，优先于官方数据）"
            for (it in items) addRow(it.udi, it.name, it.spec, it.company, "custom", true)
        }
        if (listContainer.childCount == 0) {
            val empty = TextView(this).apply {
                text = "（空）"
                setTextColor(0xFF757575.toInt())
                textSize = 13f
            }
            listContainer.addView(empty)
        }
    }

    /**
     * 单条字典行（卡片化）：
     * - 左侧色条区分来源（NMPA 蓝 / 自定义 紫），整行浅色背景呼应；
     * - 型号(规格)作 headline 醒目显示，名称/UDI 退为次要信息；
     * - 状态为 ok 时隐藏；自定义以「自定义」标签显示；
     * - 删除按钮固定右侧，编辑(自定义)也在右侧。
     */
    private fun addRow(
        udi: String, name: String?, spec: String?, company: String?, state: String, editable: Boolean
    ) {
        val ctx = this
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // 来源配色：NMPA=蓝，自定义=紫
        val accent = if (editable) 0xFF7B1FA2.toInt() else 0xFF1565C0.toInt()
        val bgTint = if (editable) 0xFFF3E5F5.toInt() else 0xFFE3F2FD.toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgTint)
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            p.bottomMargin = dp(8)
            layoutParams = p
        }

        // 左侧色条
        val bar = android.view.View(ctx).apply { setBackgroundColor(accent) }
        bar.layoutParams = LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT)

        // 内容列（占满剩余宽度）
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        // 型号(规格)作为 headline，最醒目；无型号时回退到名称
        val modelText = spec?.takeIf { it.isNotBlank() } ?: (name ?: "（无名称）")
        val model = TextView(ctx).apply {
            text = modelText
            textSize = 15f
            setTextColor(0xFF212121.toInt())
            setTextStyleBold()
        }
        col.addView(model)

        // 有型号时，名称作为次要行单独显示
        if (!spec.isNullOrBlank() && !name.isNullOrBlank()) {
            val nameTv = TextView(ctx).apply {
                text = name
                textSize = 13f
                setTextColor(0xFF616161.toInt())
                setPadding(0, 2, 0, 0)
            }
            col.addView(nameTv)
        }

        // UDI（等宽字体）
        val udiTv = TextView(ctx).apply {
            text = "UDI $udi"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 4, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        col.addView(udiTv)

        // 状态：ok 不显示；自定义 →「自定义」；其余按语义显示
        if (state != "ok") {
            val stateLabel = when (state) {
                "custom" -> "自定义"
                "pending" -> "待核对"
                "skip" -> "无记录"
                "err" -> "查询失败"
                "local" -> "本地"
                else -> state
            }
            val stateTv = TextView(ctx).apply {
                text = stateLabel
                textSize = 11f
                setTextColor(accent)
                setPadding(0, 4, 0, 0)
                setTextStyleBold()
            }
            col.addView(stateTv)
        }

        // 官方数据提示（不占右侧按钮区，放内容列底部）
        if (!editable) {
            val note = TextView(ctx).apply {
                text = "官方数据，不可编辑；改名请用「自定义字典」覆盖"
                textSize = 11f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(0, 4, 0, 0)
            }
            col.addView(note)
        }

        // 右侧操作区：编辑(自定义) + 删除，靠右排列
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            setPadding(dp(4), dp(6), dp(10), dp(6))
        }
        actions.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        if (editable) {
            val btnEdit = Button(ctx).apply {
                text = "编辑"
                textSize = 12f
                setMinDp(56, 32)
                setOnClickListener { editEntry(udi) }
            }
            actions.addView(btnEdit)
        }
        val btnDel = Button(ctx).apply {
            text = "删除"
            textSize = 12f
            setMinDp(56, 32)
            setTextColor(0xFFC62828.toInt())
            setOnClickListener { if (editable) deleteEntry(udi) else deleteNmpaEntry(udi) }
        }
        actions.addView(btnDel)

        row.addView(bar)
        row.addView(col)
        row.addView(actions)
        listContainer.addView(row)
    }

    private fun editEntry(udi: String) {
        val existing = NmpaCache.getOverride(udi)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_product, null)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_name)
        val etSpec = view.findViewById<android.widget.EditText>(R.id.et_spec)
        val etCompany = view.findViewById<android.widget.EditText>(R.id.et_company)
        etName.setText(existing?.productName ?: "")
        etSpec.setText(existing?.specification ?: "")
        etCompany.setText(existing?.companyName ?: "")
        AlertDialog.Builder(this)
            .setTitle("编辑自定义字典")
            .setMessage("UDI(01): $udi")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().let { if (it.isEmpty()) null else it }
                val spec = etSpec.text.toString().trim().let { if (it.isEmpty()) null else it }
                val company = etCompany.text.toString().trim().let { if (it.isEmpty()) null else it }
                NmpaCache.putOverride(udi, name, spec, company)
                toast("已保存")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEntry(udi: String) {
        AlertDialog.Builder(this)
            .setTitle("删除条目")
            .setMessage("确定从自定义字典删除 UDI $udi？")
            .setPositiveButton("删除") { _, _ ->
                NmpaCache.deleteOverride(udi)
                toast("已删除")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 删除单条官方 NMPA 缓存（NMPA 字典仅支持删除）。 */
    private fun deleteNmpaEntry(udi: String) {
        AlertDialog.Builder(this)
            .setTitle("删除 NMPA 缓存")
            .setMessage("确定从 NMPA 字典库删除 UDI $udi？删除后下次查询会重新联网。")
            .setPositiveButton("删除") { _, _ ->
                NmpaCache.deleteCache(udi)
                toast("已删除")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新增自定义字典条目（UDI 主键 + 名称/型号/厂家，可空）。 */
    private fun addCustomEntry() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_custom, null)
        val etUdi = view.findViewById<android.widget.EditText>(R.id.et_udi)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_name)
        val etSpec = view.findViewById<android.widget.EditText>(R.id.et_spec)
        val etCompany = view.findViewById<android.widget.EditText>(R.id.et_company)
        AlertDialog.Builder(this)
            .setTitle("新增自定义条目")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val udi = etUdi.text.toString().trim()
                if (udi.isEmpty()) { toast("UDI 不能为空"); return@setPositiveButton }
                val name = etName.text.toString().trim().let { if (it.isEmpty()) null else it }
                val spec = etSpec.text.toString().trim().let { if (it.isEmpty()) null else it }
                val company = etCompany.text.toString().trim().let { if (it.isEmpty()) null else it }
                NmpaCache.putOverride(udi, name, spec, company)
                toast("已新增")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun android.widget.TextView.setTextStyleBold() {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun Button.setMinDp(w: Int, h: Int) {
        minWidth = (w * resources.displayMetrics.density).toInt()
        minHeight = (h * resources.displayMetrics.density).toInt()
        setPadding(12, 0, 12, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
