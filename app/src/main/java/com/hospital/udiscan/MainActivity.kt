package com.hospital.udiscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var tvBuffer: TextView
    private lateinit var tvProduct: TextView
    private lateinit var tvQty: TextView
    private lateinit var listItems: RecyclerView
    private lateinit var btnPlus: Button
    private lateinit var btnMinus: Button
    private lateinit var btnQuery: Button
    private lateinit var btnAdd: Button
    private lateinit var btnDiscard: Button
    private lateinit var btnExportJson: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnClear: Button
    private lateinit var btnDict: Button

    private lateinit var adapter: ItemAdapter

    private val items = mutableListOf<ScanItem>()

    // —— 当前缓冲（待录入，跨帧/多码累积合并）——
    private val scannedRaws = mutableSetOf<String>()
    private var bufUdi: String? = null
    private var bufBatch: String? = null
    private var bufExpiry: String? = null
    private var bufProduction: String? = null
    private var bufSerial: String? = null
    private var bufSerialAi: String? = null
    private var bufProduct: String? = null
    private var bufSpec: String? = null
    private var bufCompany: String? = null
    private var bufNmpaState: String = "none"
    private var bufQty: Int = 1
    private var queriedUdi: String? = null

    private val camPerm = Manifest.permission.CAMERA
    private val rcCam = 1001
    private val rcImport = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NmpaCache.init(this)

        scanner = findViewById(R.id.barcode_scanner)
        tvBuffer = findViewById(R.id.tv_buffer)
        tvProduct = findViewById(R.id.tv_product)
        tvQty = findViewById(R.id.tv_qty)
        listItems = findViewById(R.id.list_items)
        btnPlus = findViewById(R.id.btn_plus)
        btnMinus = findViewById(R.id.btn_minus)
        btnQuery = findViewById(R.id.btn_query)
        btnAdd = findViewById(R.id.btn_add)
        btnDiscard = findViewById(R.id.btn_discard)
        btnExportJson = findViewById(R.id.btn_export_json)
        btnExportCsv = findViewById(R.id.btn_export_csv)
        btnClear = findViewById(R.id.btn_clear)
        btnDict = findViewById(R.id.btn_dict)

        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                onScanned(text)
            }

            override fun possibleResultPoints(points: List<com.google.zxing.ResultPoint>) {}
        })

        adapter = ItemAdapter(items,
            onDelete = { pos ->
                items.removeAt(pos)
                adapter.notifyItemRemoved(pos)
            },
            onEdit = { pos -> editListItem(pos) }
        )
        listItems.layoutManager = LinearLayoutManager(this)
        listItems.adapter = adapter

        btnPlus.setOnClickListener { bufQty++; updateBufferUi() }
        btnMinus.setOnClickListener { if (bufQty > 1) bufQty--; updateBufferUi() }
        btnQuery.setOnClickListener {
            if (!bufUdi.isNullOrEmpty()) queryNmpa(bufUdi!!) else toast(R.string.toast_no_udi)
        }
        btnAdd.setOnClickListener { commitBuffer() }
        btnDiscard.setOnClickListener { clearBuffer() }
        btnExportJson.setOnClickListener { exportJson() }
        btnExportCsv.setOnClickListener { exportCsv() }
        btnClear.setOnClickListener { clearList() }
        btnDict.setOnClickListener { openDictManager() }

        // 长按缓冲「产品」区 → 直接编辑当前 UDI 字典
        tvProduct.setOnClickListener {
            if (!bufUdi.isNullOrEmpty()) editCurrentProduct()
        }

        updateBufferUi()
    }

    // ——— 扫码回调：合并到缓冲 ———
    private fun onScanned(text: String) {
        if (!scannedRaws.add(text)) return   // 本次缓冲内重复，跳过（连续扫码同一帧）

        // 让解析器按 FNC1/括号/数字 自动判定 GS1，无需依赖元数据标记
        val res = Gs1Parser.parse(text)
        if (!res.isGs1) {
            if (bufUdi == null && bufBatch == null && bufExpiry == null && bufSerial == null) {
                bufSerial = text
                bufSerialAi = "91"
                updateBufferUi()
            }
            return
        }

        res.fields["01"]?.let { if (bufUdi == null) bufUdi = it }
        res.fields["10"]?.let { if (bufBatch == null) bufBatch = it }
        res.fields["17"]?.let { if (bufExpiry == null) bufExpiry = it }
        res.fields["11"]?.let { if (bufProduction == null) bufProduction = it }
        val s21 = res.fields["21"]
        val s91 = res.fields["91"]
        when {
            s21 != null && bufSerial == null -> { bufSerial = s21; bufSerialAi = "21" }
            s91 != null && bufSerial == null -> { bufSerial = s91; bufSerialAi = "91" }
        }
        updateBufferUi()

        if (!bufUdi.isNullOrEmpty() && bufUdi != queriedUdi) {
            queriedUdi = bufUdi
            queryNmpa(bufUdi!!)
        }
    }

    private fun updateBufferUi() {
        val b = StringBuilder()
        b.append("UDI(01): ").append(bufUdi ?: "—").append("\n")
        b.append("批号(10): ").append(bufBatch ?: "—").append("    ")
        b.append("效期(17): ").append(Gs1Parser.formatDateYYMMDD(bufExpiry) ?: bufExpiry ?: "—").append("\n")
        b.append("生产(11): ").append(Gs1Parser.formatDateYYMMDD(bufProduction) ?: bufProduction ?: "—").append("    ")
        b.append("序列(${bufSerialAi ?: "?"}): ").append(bufSerial ?: "—")
        tvBuffer.text = b.toString()
        tvProduct.text = when (bufNmpaState) {
            "ok" -> "✓ ${bufProduct ?: ""}"
            "local" -> "✎本地字典：${bufProduct ?: ""}"
            "pending" -> "⚠ 待核对：${bufProduct ?: ""}"
            "skip" -> "✗ NMPA 无记录"
            "err" -> "✗ 查询失败"
            else -> bufProduct ?: ""
        }
        tvQty.text = bufQty.toString()
    }

    private fun queryNmpa(udi: String) {
        bufNmpaState = "querying"
        updateBufferUi()
        toast(R.string.toast_querying)
        Thread {
            // 1) 本地合并读取：override（手改/手补）优先，其次官方缓存
            val (local, fromOverride) = NmpaCache.getMerged(udi)
            val r = if (local != null) {
                if (fromOverride) local.copy(state = "local") else local
            } else {
                // 2) 本地均无 → 联网查 NMPA，成功后落官方缓存
                val net = NmpaClient.query(udi)
                if (net.state != "err") NmpaCache.put(udi, net)
                net
            }
            runOnUiThread {
                if (bufUdi != udi) return@runOnUiThread
                bufNmpaState = r.state
                bufProduct = r.productName
                bufSpec = r.specification
                bufCompany = r.companyName
                updateBufferUi()
                when (r.state) {
                    "ok" -> toast(getString(R.string.toast_query_ok, r.productName ?: ""))
                    "local" -> toast(R.string.toast_query_local)
                    "pending" -> toast(R.string.toast_query_pending)
                    "skip" -> toast(R.string.toast_query_skip)
                    "err" -> toast(R.string.toast_query_err)
                }
            }
        }.start()
    }

    private fun commitBuffer() {
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
            raw = scannedRaws.joinToString(" | "),
            scannedAt = ScanItem.nowStamp()
        )
        items.add(0, item)
        adapter.notifyItemInserted(0)
        listItems.scrollToPosition(0)
        clearBuffer()
        if (item.udiDi.isNullOrEmpty()) toast(R.string.toast_no_udi)
    }

    // ——— 编辑已有清单条目：写 override 并就地刷新 item ———
    private fun editListItem(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        val it = items[pos]
        val udi = it.udiDi ?: run { toast(R.string.toast_no_udi); return }
        val existing = NmpaCache.getOverride(udi)
                ?: NmpaCache.get(udi)
        showEditDialog(udi, existing?.productName ?: it.productName,
                existing?.specification ?: it.specification,
                existing?.companyName ?: it.companyName)
        // 对话框保存后，就地同步到该条目（保证导出也是修正后的值）
        // 注意：showEditDialog 内部已写 DB；这里在返回后直接按需刷新展示字段
        // 为避免重复弹窗逻辑，采用“同意保存后”通过一次轻量重查覆盖：
        Thread {
            val merged = NmpaCache.getMerged(udi).first
            runOnUiThread {
                it.productName = merged?.productName ?: it.productName
                it.specification = merged?.specification ?: it.specification
                it.companyName = merged?.companyName ?: it.companyName
                it.nmpaState = if (NmpaCache.getOverride(udi) != null) "local" else it.nmpaState
                adapter.notifyItemChanged(pos)
            }
        }.start()
    }

    private fun clearBuffer() {
        scannedRaws.clear()
        bufUdi = null; bufBatch = null; bufExpiry = null; bufProduction = null
        bufSerial = null; bufSerialAi = null
        bufProduct = null; bufSpec = null; bufCompany = null
        bufNmpaState = "none"; bufQty = 1; queriedUdi = null
        updateBufferUi()
    }

    // ——— 导出 ———
    private fun exportDir(): File {
        val d = File(filesDir, "exports")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun exportJson() {
        if (items.isEmpty()) { toast(R.string.toast_list_empty); return }
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("udi_di", it.udiDi ?: "")
                put("batch", it.batch ?: "")
                put("expiry", it.expiry ?: "")
                put("production", it.production ?: "")
                put("serial", it.serial ?: "")
                put("serial_ai", it.serialAi ?: "")
                put("product_name", it.productName ?: "")
                put("specification", it.specification ?: "")
                put("company_name", it.companyName ?: "")
                put("nmpa_state", it.nmpaState)
                put("quantity", it.quantity)
                put("raw", it.raw)
                put("scanned_at", it.scannedAt)
            })
        }
        val file = File(exportDir(), "udi_scan_${System.currentTimeMillis()}.json")
        file.writeText(arr.toString(2), Charsets.UTF_8)
        shareFile(file, "application/json")
    }

    private fun exportCsv() {
        if (items.isEmpty()) { toast(R.string.toast_list_empty); return }
        val header = listOf(
            "UDI-DI", "产品名称", "规格", "厂家", "批号", "效期",
            "生产日期", "序列号", "序列AI", "NMPA状态", "数量", "扫码时间"
        )
        val sb = StringBuilder()
        sb.append(header.joinToString(",")).append("\n")
        for (it in items) {
            val row = listOf(
                it.udiDi ?: "", it.productName ?: "", it.specification ?: "", it.companyName ?: "",
                it.batch ?: "", Gs1Parser.formatDateYYMMDD(it.expiry) ?: (it.expiry ?: ""),
                Gs1Parser.formatDateYYMMDD(it.production) ?: (it.production ?: ""),
                it.serial ?: "", it.serialAi ?: "", it.nmpaState, it.quantity.toString(), it.scannedAt
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append("\n")
        }
        val file = File(exportDir(), "udi_scan_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        shareFile(file, "text/csv")
    }

    private fun csvCell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 / 分享"))
        toast(R.string.toast_exported)
    }

    // ——— 编辑当前缓冲产品（写入 override）———
    private fun editCurrentProduct() {
        val udi = bufUdi ?: run { toast(R.string.toast_no_udi); return }
        // 先读已有覆盖值作默认值
        val existing = NmpaCache.getOverride(udi)
                ?: NmpaCache.get(udi)
        showEditDialog(udi, existing?.productName, existing?.specification, existing?.companyName)
    }

    /**
     * 编辑对话框：改名/规格/厂家，写入 udi_override。
     * 不论该 UDI 之前是官方查回还是查不到，都会被用户覆盖生效（override 优先）。
     */
    private fun showEditDialog(udi: String, defName: String?, defSpec: String?, defCompany: String?) {
        val ctx = this
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_product, null)
        val etName = view.findViewById<EditText>(R.id.et_name)
        val etSpec = view.findViewById<EditText>(R.id.et_spec)
        val etCompany = view.findViewById<EditText>(R.id.et_company)
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
                // 同步刷新缓冲展示
                if (bufUdi == udi) {
                    bufProduct = name ?: bufProduct
                    bufSpec = spec ?: bufSpec
                    bufCompany = company ?: bufCompany
                    if (bufNmpaState == "skip" || bufNmpaState == "err" || bufNmpaState == "none") {
                        bufNmpaState = "local"
                    }
                    updateBufferUi()
                }
                toast(R.string.toast_saved)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ——— 字典管理：导出/导入 override（多设备同步）———
    private fun openDictManager() {
        val ctx = this
        val msg = "本地覆盖字典：${NmpaCache.overrideCount()} 条\n" +
                "NMPA 官方缓存：${NmpaCache.count()} 条\n\n" +
                "导出：把覆盖字典存成 udi_overrides.json，通过微信/网盘发给其他设备。\n" +
                "导入：选择其他设备发来的 udi_overrides.json，合并到本机。"
        AlertDialog.Builder(ctx)
            .setTitle("产品字典管理")
            .setMessage(msg)
            .setPositiveButton("导出字典") { _, _ -> exportOverrides() }
            .setNeutralButton("导入字典") { _, _ -> importOverrides() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportOverrides() {
        val json = NmpaCache.exportOverridesJson()
        val file = File(exportDir(), "udi_overrides.json")
        file.writeText(json, Charsets.UTF_8)
        shareFile(file, "application/json")
        toast(R.string.toast_exported)
    }

    private fun importOverrides() {
        // 拉起文件选择（含微信/网盘接收的文件）
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(Intent.createChooser(intent, "选择 udi_overrides.json"), rcImport)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == rcImport && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            Thread {
                var n = 0
                try {
                    val sb = StringBuilder()
                    contentResolver.openInputStream(uri)?.use { ins ->
                        BufferedReader(ins.reader(Charsets.UTF_8)).useLines { lines ->
                            lines.forEach { sb.append(it) }
                        }
                    }
                    val arr = JSONArray(sb.toString())
                    val list = mutableListOf<NmpaCache.OverrideEntry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(NmpaCache.OverrideEntry(
                            udi = o.optString("udi", ""),
                            name = o.optString("name", "").let { if (it.isEmpty()) null else it },
                            spec = o.optString("spec", "").let { if (it.isEmpty()) null else it },
                            company = o.optString("company", "").let { if (it.isEmpty()) null else it }
                        ))
                    }
                    n = NmpaCache.importOverrides(list)
                } catch (e: Exception) {
                    runOnUiThread { toast(R.string.toast_import_err) }
                    return@Thread
                }
                runOnUiThread {
                    toast(getString(R.string.toast_imported, n))
                }
            }.start()
        }
    }

    private fun clearList() {
        if (items.isEmpty()) return
        items.clear()
        adapter.notifyDataSetChanged()
    }

    // ——— 相机权限 ———
    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, camPerm) == PackageManager.PERMISSION_GRANTED) {
            scanner.resume()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(camPerm), rcCam)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == rcCam && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanner.resume()
        } else if (requestCode == rcCam) {
            toast(R.string.toast_no_camera)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureCamera()
    }

    override fun onPause() {
        scanner.pause()
        super.onPause()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
