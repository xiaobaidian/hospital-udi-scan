package com.hospital.udiscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
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
    private lateinit var btnManage: Button
    private lateinit var scanFlash: android.widget.TextView

    private lateinit var adapter: ItemAdapter

    private val items get() = ScanStore.items

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
    private var bufNmpaState: String = "  "
    private var bufQty: Int = 1
    private var queriedUdi: String? = null
    // 待确认（未知段）：缓冲还没有 UDI 时，暂存未归类的纯数字/文本，供「加入清单」时再提示
    private var bufPendingUnknown: List<String> = emptyList()

    // —— 扫码闪光反馈 ——
    private var feedbackTimer: android.os.Handler? = null

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
        btnManage = findViewById(R.id.btn_manage)
        scanFlash = findViewById(R.id.scan_flash)

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
        btnManage.setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }

        // 长按缓冲「产品」区 → 直接编辑当前 UDI 字典
        tvProduct.setOnClickListener {
            if (!bufUdi.isNullOrEmpty()) editCurrentProduct()
        }

        updateBufferUi()
    }

    // ——— 扫码回调：按字段「语义类型」合并到缓冲（而非按出现顺序填坑）———
    private fun onScanned(text: String) {
        if (!scannedRaws.add(text)) return   // 本次缓冲内重复，跳过（连续扫码同一帧）

        val parsed = Gs1Parser.parse(text, alreadyHasUdi = bufUdi != null)
        if (parsed.fields.isEmpty()) {
            // 连纯文本都不是：当作未知，提示用户
            flashScanFeedback("无法识别", false)
            return
        }
        beep()  // 识别到有效字段即「滴」一声

        // 按类型合并：每类只取第一个尚未填的，避免重复覆盖用户已确认的值
        for (f in parsed.fields) {
            when (f.type) {
                Gs1Parser.FieldType.UDI ->
                    if (bufUdi == null) bufUdi = f.value
                Gs1Parser.FieldType.BATCH ->
                    if (bufBatch == null) bufBatch = f.value
                Gs1Parser.FieldType.EXPIRY ->
                    if (bufExpiry == null) bufExpiry = f.value
                Gs1Parser.FieldType.PROD_DATE ->
                    if (bufProduction == null) bufProduction = f.value
                Gs1Parser.FieldType.SERIAL ->
                    if (bufSerial == null) { bufSerial = f.value; bufSerialAi = f.ai ?: "21" }
                Gs1Parser.FieldType.UNKNOWN -> Unit  // 未知稍后处理
            }
        }
        // 未明确归类的未知段（如纯数字歧义），若缓冲还没 UDI，先暂存为「待确认」
        val unknowns = parsed.fields.filter { it.type == Gs1Parser.FieldType.UNKNOWN }
        if (unknowns.isNotEmpty() && bufUdi == null) {
            bufPendingUnknown = unknowns.map { it.value }
        }

        flashScanFeedback(describeScan(parsed, unknowns), true)
        updateBufferUi()

        if (!bufUdi.isNullOrEmpty() && bufUdi != queriedUdi) {
            queriedUdi = bufUdi
            queryNmpa(bufUdi!!)
        }
    }

    /** 生成「识别为：xxx」的简短反馈文字。 */
    private fun describeScan(res: Gs1Parser.Gs1Result, unknowns: List<Gs1Parser.Field>): String {
        val types = res.fields.mapNotNull { labelOf(it.type) }
        val extra = if (unknowns.isNotEmpty()) " · 待确认 ${unknowns.size} 段" else ""
        return "识别：" + (types.distinct().joinToString(" ") { "「$it」" }) + extra
    }

    private fun labelOf(t: Gs1Parser.FieldType): String = when (t) {
        Gs1Parser.FieldType.UDI -> "UDI"
        Gs1Parser.FieldType.BATCH -> "批号"
        Gs1Parser.FieldType.EXPIRY -> "效期"
        Gs1Parser.FieldType.PROD_DATE -> "生产"
        Gs1Parser.FieldType.SERIAL -> "序列号"
        Gs1Parser.FieldType.UNKNOWN -> "未归类"
    }

    private fun updateBufferUi() {
        val b = StringBuilder()
        appendField(b, "UDI(01)", bufUdi, true)
        appendField(b, "批号(10)", bufBatch)
        appendField(b, "效期(17)", Gs1Parser.formatDateYYMMDD(bufExpiry) ?: bufExpiry)
        appendField(b, "生产(11)", Gs1Parser.formatDateYYMMDD(bufProduction) ?: bufProduction)
        appendField(b, "序列(${bufSerialAi ?: "?"}", bufSerial)
        if (bufPendingUnknown.isNotEmpty()) {
            b.append("\n⚠ 待确认：").append(bufPendingUnknown.joinToString(" / "))
        }
        tvBuffer.text = b.toString()
        tvProduct.text = when (  bufNmpaState) {
            "ok" -> "✓ ${bufProduct ?: ""}"
            "local" -> "✎本地字典：${bufProduct ?: ""}"
            "pending" -> "⚠ 待核对：${bufProduct ?: ""}"
            "skip" -> "✗ NMPA 无记录"
            "err" -> "✗ 查询失败"
            "querying" -> "… 查询中"
            else -> bufProduct ?: ""
        }
        tvQty.text = bufQty.toString()
    }

    private fun appendField(b: StringBuilder, label: String, value: String?, strong: Boolean = false) {
        val v = value ?: "—"
        if (strong) b.append("► $label: $v\n") else b.append("  $label: $v\n")
    }

    /** 扫码成功/失败时的视觉反馈：顶部闪光条 + 文字提示。 */
    private fun flashScanFeedback(msg: String, ok: Boolean) {
        scanFlash?.let { flash ->
            flash.text = msg
            flash.visibility = android.view.View.VISIBLE
            flash.setBackgroundColor(
                if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
            flash.alpha = 1f
            feedbackTimer?.removeCallbacksAndMessages(null)
            feedbackTimer = android.os.Handler(android.os.Looper.getMainLooper()).apply {
                postDelayed({ flash.visibility = android.view.View.GONE }, 1400)
            }
        }
    }

    /** 扫码成功（识别到有效字段）时播放系统提示音，零依赖、无需额外素材。 */
    private fun beep() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone.play()
        } catch (e: Exception) {
            // 个别机型无声也不影响扫码流程
        }
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

    /** 汇总本次缓冲的原始扫码内容（含待确认段），用于导出与审计。 */
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
            raw = buildRaw(),
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
        bufPendingUnknown = emptyList()
        updateBufferUi()
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
