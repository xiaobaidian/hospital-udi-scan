package com.hospital.udiscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import java.io.File
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

        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                onScanned(text)
            }

            override fun possibleResultPoints(points: List<com.google.zxing.ResultPoint>) {}
        })

        adapter = ItemAdapter(items) { pos ->
            items.removeAt(pos)
            adapter.notifyItemRemoved(pos)
        }
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
            // 1) 先查本地缓存（落过库的直接复用，不再联网）
            val cached = NmpaCache.get(udi)
            val r = if (cached != null) {
                cached
            } else {
                // 2) 缓存未命中 → 联网查，成功后落库
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
