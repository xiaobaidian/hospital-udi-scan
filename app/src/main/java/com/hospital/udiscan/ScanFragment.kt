package com.hospital.udiscan

import android.Manifest
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
import java.util.UUID

/**
 * 扫码页（ViewPager2 第 0 页）：取景 + 「本次将录入」预览卡 + 当前待录入缓冲卡。
 * 所有状态读写都经过 ScanViewModel，滑到清单页再滑回不丢失。
 * 相机生命周期随 Fragment：滑走 onPause 暂停，滑回 onResume 恢复。
 */
class ScanFragment : Fragment() {

    private lateinit var vm: ScanViewModel
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var tvProductTop: TextView
    private lateinit var etQty: android.widget.EditText
    private lateinit var btnQtyMinus: Button
    private lateinit var btnQtyPlus: Button
    private lateinit var btnQuery: Button
    private lateinit var btnAdd: Button
    private lateinit var btnDiscard: Button
    private lateinit var btnEditName: Button
    private lateinit var btnAccept: Button
    private lateinit var scanFlash: TextView
    private lateinit var tvLineUdi: TextView
    private lateinit var tvLineBatch: TextView
    private lateinit var tvLineExpiry: TextView
    private lateinit var tvLineProd: TextView
    private lateinit var tvLineSerial: TextView
    private lateinit var tvPreviewHint: TextView
    private lateinit var cardRawDump: android.view.View
    private lateinit var tvRawDump: TextView

    // —— 扫码闪光反馈 ——
    private var feedbackTimer: Handler? = null

    // —— 去抖：800ms 内同串不重复处理 ——
    private var lastBeepTime: Long = 0
    private var lastRawHandled: String? = null

    private var vibrator: Vibrator? = null

    private val camPerm = Manifest.permission.CAMERA
    private val rcCam = 1001

    override fun onCreateView(
        inflater: LayoutInflater, container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_scan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator

        scanner = view.findViewById(R.id.barcode_scanner)
        tvProductTop = view.findViewById(R.id.tv_product_top)
        etQty = view.findViewById(R.id.et_qty)
        btnQtyMinus = view.findViewById(R.id.btn_qty_minus)
        btnQtyPlus = view.findViewById(R.id.btn_qty_plus)
        btnQuery = view.findViewById(R.id.btn_query)
        btnAdd = view.findViewById(R.id.btn_add)
        btnDiscard = view.findViewById(R.id.btn_discard)
        btnEditName = view.findViewById(R.id.btn_edit_name)
        btnAccept = view.findViewById(R.id.btn_accept)
        scanFlash = view.findViewById(R.id.scan_flash)
        tvLineUdi = view.findViewById(R.id.tv_line_udi)
        tvLineBatch = view.findViewById(R.id.tv_line_batch)
        tvLineExpiry = view.findViewById(R.id.tv_line_expiry)
        tvLineProd = view.findViewById(R.id.tv_line_prod)
        tvLineSerial = view.findViewById(R.id.tv_line_serial)
        tvPreviewHint = view.findViewById(R.id.tv_preview_hint)
        cardRawDump = view.findViewById(R.id.card_raw_dump)
        tvRawDump = view.findViewById(R.id.tv_raw_dump)

        // —— 解码参数调优：提升长条码（GS1-128/Code128 高密度）识别率 ——
        // 显式启用全部一维码格式（DefaultDecoderFactory 内部自带 TRY_HARDER，
        // 对长条码、低密度、畸变条码识别率明显优于默认按场景裁剪的格式集）。
        scanner.barcodeView.decoderFactory = DefaultDecoderFactory(
            listOf(
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.ITF,
                BarcodeFormat.CODABAR,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
                BarcodeFormat.RSS_14,
                BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX
            )
        )

        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                // 自动分辨来源：一维码(Code128/39/93/ITF/CODABAR…) vs 二维码(QR/DataMatrix)
                val fmt = result.barcodeFormat
                val isQr = fmt == BarcodeFormat.QR_CODE || fmt == BarcodeFormat.DATA_MATRIX
                onScanned(text, isQr)
            }

            override fun possibleResultPoints(points: List<com.google.zxing.ResultPoint>) {}
        })

        // 数量可直接填写，实时写回 vm.bufQty
        etQty.setText(vm.bufQty.toString())
        etQty.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: android.text.Editable?) {
                val v = editable?.toString()?.toIntOrNull()
                if (v != null && v >= 1) vm.bufQty = v
            }
        })

        // 数量步进：减号 / 加号
        fun stepQty(delta: Int) {
            val cur = etQty.text.toString().toIntOrNull() ?: vm.bufQty
            val next = (cur + delta).coerceAtLeast(1)
            vm.bufQty = next
            etQty.setText(next.toString())
            etQty.setSelection(etQty.text.length)
        }
        btnQtyMinus.setOnClickListener { stepQty(-1) }
        btnQtyPlus.setOnClickListener { stepQty(1) }
        btnQuery.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) queryNmpa(udi) else toast(R.string.toast_no_udi)
        }
        btnAdd.setOnClickListener { commitBuffer(); updateBufferUi() }
        btnDiscard.setOnClickListener {
            vm.clearBuffer()
            updateBufferUi()
            toast(R.string.toast_discarded)
        }

        btnEditName.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) editCurrentProduct(udi)
        }
        btnAccept.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) acceptPending(udi)
        }

        updateBufferUi()
    }

    override fun onResume() {
        super.onResume()
        ensureCamera()
    }

    override fun onPause() {
        scanner.pause()
        super.onPause()
    }

    // ——— 扫码回调：按字段「语义类型」合并到缓冲（而非按出现顺序填坑）———
    private fun onScanned(text: String, isQr: Boolean = false) {
        val now = System.currentTimeMillis()
        if (text == lastRawHandled && now - lastBeepTime < 800) return
        if (text.trim().length < 4) return
        if (!vm.scannedRaws.add(text)) return
        lastRawHandled = text
        lastBeepTime = now

        val parsed = Gs1Parser.parse(text, alreadyHasUdi = vm.bufUdi != null)
        if (parsed.fields.isEmpty()) {
            flashScanFeedback("无法识别", false)
            return
        }
        val hasValid = parsed.fields.any { it.type != Gs1Parser.FieldType.UNKNOWN }
        if (!hasValid) {
            flashScanFeedback("未识别，已忽略", false)
            return
        }
        beep()

        // 记录本次来源（条码 / 二维码），供缓冲卡显示
        vm.bufSource = if (isQr) "qr" else "barcode"

        val newUdi = parsed.fields.firstOrNull { it.type == Gs1Parser.FieldType.UDI }?.value
        if (newUdi != null && vm.bufUdi != null && newUdi != vm.bufUdi) {
            flashScanFeedback("⚠ 已扫到不同 UDI，请先『加入清单』", false)
            toast(R.string.toast_udi_conflict)
            return
        }

        for (f in parsed.fields) {
            when (f.type) {
                Gs1Parser.FieldType.UDI ->
                    if (vm.bufUdi == null) vm.bufUdi = f.value
                Gs1Parser.FieldType.BATCH ->
                    if (vm.bufBatch == null) vm.bufBatch = f.value
                Gs1Parser.FieldType.EXPIRY ->
                    if (vm.bufExpiry == null) vm.bufExpiry = f.value
                Gs1Parser.FieldType.PROD_DATE ->
                    if (vm.bufProduction == null) vm.bufProduction = f.value
                Gs1Parser.FieldType.SERIAL ->
                    if (vm.bufSerial == null) { vm.bufSerial = f.value; vm.bufSerialAi = f.ai ?: "21" }
                Gs1Parser.FieldType.UNKNOWN -> Unit
            }
        }
        val unknowns = parsed.fields.filter { it.type == Gs1Parser.FieldType.UNKNOWN }
        if (unknowns.isNotEmpty() && vm.bufUdi == null) {
            vm.bufPendingUnknown = unknowns.map { it.value }
        }

        flashScanFeedback(describeScan(parsed, unknowns), true)
        updateBufferUi()

        val udi = vm.bufUdi
        if (!udi.isNullOrEmpty() && udi != vm.queriedUdi) {
            vm.queriedUdi = udi
            queryNmpa(udi)
        }
    }

    private fun describeScan(res: Gs1Parser.Gs1Result, unknowns: List<Gs1Parser.Field>): String {
        val types = res.fields.mapNotNull { labelOf(it.type) }
        val extra = if (unknowns.isNotEmpty()) " · 待确认 ${unknowns.size} 段" else ""
        val src = if (vm.bufSource == "qr") "二维码" else "条码"
        return "[$src] 识别：" + (types.distinct().joinToString(" ") { "「$it」" }) + extra
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
        // 顶部醒目横条：查询到的名称 + 型号（型号另起一行，更直观）
        val namePart = when (vm.bufNmpaState) {
            "ok" -> "✓ ${vm.bufProduct ?: ""}"
            "local" -> "✎ 本地字典：${vm.bufProduct ?: ""}"
            "pending" -> "⚠ 待核对：${vm.bufProduct ?: ""}"
            "skip" -> "✗ NMPA 无记录"
            "err" -> "✗ 查询失败"
            "querying" -> "… 查询中"
            else -> vm.bufProduct ?: ""
        }
        val specPart = if (!vm.bufSpec.isNullOrEmpty()) "型号：${vm.bufSpec}" else ""
        val topText = if (namePart.isEmpty()) specPart else if (specPart.isEmpty()) namePart else "$namePart\n$specPart"
        if (topText.isEmpty()) {
            tvProductTop.visibility = View.GONE
        } else {
            tvProductTop.visibility = View.VISIBLE
            tvProductTop.text = topText
        }
        updatePreviewCard()
    }

    /** 更新「本次将录入」预览卡（紧凑分行展示）。 */
    private fun updatePreviewCard() {
        // 解析前拼接的原始扫码串卡片：扫码前隐藏，扫到码再展示；按 UDI 优先排序
        val dump = vm.rawDumpSorted()
        if (dump.isEmpty()) {
            cardRawDump.visibility = View.GONE
        } else {
            cardRawDump.visibility = View.VISIBLE
            tvRawDump.text = dump.joinToString("\n") { (raw, hasUdi) ->
                if (hasUdi) "★ $raw" else "· $raw"
            }
        }
        // 「自定义字典」按钮：仅扫到 UDI 才展示（粉色底白字）
        btnEditName.visibility = if (vm.bufUdi.isNullOrEmpty()) View.GONE else View.VISIBLE
        // 「✓ 接受候选」按钮：仅查询结果为「待核对(pending)」且已扫到 UDI 时展示
        btnAccept.visibility = if (vm.bufUdi.isNullOrEmpty() || vm.bufNmpaState != "pending") View.GONE else View.VISIBLE
        // 来源标记：自动分辨条码/二维码
        val src = if (vm.bufSource == "qr") "📷 二维码" else "▌ 条码"
        // 数量回填（仅在用户未在编辑时同步，避免打断输入）
        val cur = etQty.text.toString().toIntOrNull()
        if (cur == null || cur < 1 || cur != vm.bufQty) {
            etQty.setText(vm.bufQty.toString())
        }
        // 「本次将录入」逐行：有内容才显示，空则隐藏不占位；每行各自背景色已在布局定义。
        if (vm.bufUdi.isNullOrEmpty()) tvLineUdi.visibility = View.GONE
        else { tvLineUdi.visibility = View.VISIBLE; tvLineUdi.text = "UDI(01)：${vm.bufUdi}" }

        if (vm.bufBatch.isNullOrEmpty()) tvLineBatch.visibility = View.GONE
        else { tvLineBatch.visibility = View.VISIBLE; tvLineBatch.text = "批号(10)：${vm.bufBatch}" }

        val exp = Gs1Parser.formatDateYYMMDD(vm.bufExpiry) ?: vm.bufExpiry
        if (exp.isNullOrEmpty()) tvLineExpiry.visibility = View.GONE
        else { tvLineExpiry.visibility = View.VISIBLE; tvLineExpiry.text = "效期(17)：${exp}" }

        val prod = Gs1Parser.formatDateYYMMDD(vm.bufProduction) ?: vm.bufProduction
        if (prod.isNullOrEmpty()) tvLineProd.visibility = View.GONE
        else { tvLineProd.visibility = View.VISIBLE; tvLineProd.text = "生产(11)：${prod}" }

        val serialAi = vm.bufSerialAi ?: "21"
        if (vm.bufSerial.isNullOrEmpty()) {
            tvLineSerial.visibility = View.GONE
        } else {
            tvLineSerial.visibility = View.VISIBLE
            tvLineSerial.text = "序列($serialAi)：${vm.bufSerial}"
        }

        if (vm.bufPendingUnknown.isNotEmpty()) {
            tvPreviewHint.text = getString(R.string.preview_hint_pending, vm.bufPendingUnknown.joinToString(" / "))
            tvPreviewHint.visibility = View.VISIBLE
        } else {
            tvPreviewHint.text = getString(R.string.preview_hint_empty)
            tvPreviewHint.visibility = View.VISIBLE
        }
    }

    /** 扫码成功/失败时的视觉反馈：顶部闪光条 + 文字提示。 */
    private fun flashScanFeedback(msg: String, ok: Boolean) {
        scanFlash?.let { flash ->
            flash.text = msg
            flash.visibility = View.VISIBLE
            flash.setBackgroundColor(
                if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
            flash.alpha = 1f
            feedbackTimer?.removeCallbacksAndMessages(null)
            feedbackTimer = Handler(Looper.getMainLooper()).apply {
                postDelayed({ flash.visibility = View.GONE }, 1400)
            }
        }
    }

    /** 扫码成功（识别到有效字段）时播放系统提示音 + 震动，零依赖。 */
    private fun beep() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(requireContext(), uri)?.play()
        } catch (e: Exception) { }
        try {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(30)
                }
            }
        } catch (e: Exception) { }
    }

    private fun queryNmpa(udi: String) {
        vm.bufNmpaState = "querying"
        updateBufferUi()
        toast(R.string.toast_querying)
        Thread {
            val (local, fromOverride) = NmpaCache.getMerged(udi)
            val r = if (local != null) {
                if (fromOverride) local.copy(state = "local") else local
            } else {
                val net = NmpaClient.query(udi)
                // 仅「已查到(ok)」才落官方 NMPA 字典；skip/pending/err 不进官方字典。
                // 无记录且用户后续手改时，才会进入「自定义字典」(override)。
                if (net.state == "ok") NmpaCache.put(udi, net)
                net
            }
            activity?.runOnUiThread {
                if (vm.bufUdi != udi) return@runOnUiThread
                vm.bufNmpaState = r.state
                vm.bufProduct = r.productName
                vm.bufSpec = r.specification
                vm.bufCompany = r.companyName
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
        val item = vm.commitBuffer()
        if (item.udiDi.isNullOrEmpty()) toast(R.string.toast_no_udi)
        // 加入清单后，若当前在扫码页，提示已加入（用户可左滑查看清单）
        toast(R.string.toast_added)
    }

    // ——— 编辑当前缓冲产品（写入 override，并同步已录入的同 UDI 条目）———
    private fun editCurrentProduct(udi: String) {
        val existing = NmpaCache.getOverride(udi) ?: NmpaCache.get(udi)
        showEditDialog(udi, existing?.productName, existing?.specification, existing?.companyName) { name, spec, company ->
            // 写 override 落库 + 同步清单里同 UDI 条目
            NmpaCache.putOverride(udi, name, spec, company)
            vm.updateAllByUdi(udi, name, spec, company)
        }
    }

    /**
     * 接受一条「待核对(pending)」候选：把 NMPA 返回的候选名称/型号/厂家写入自定义字典（override），
     * 不污染官方 NMPA 库；状态转为「本地字典(local)」，下次同 UDI 直接命中。
     * 待核对结果本身不缓存（每次仍重查），仅用户的明确接受被持久化。
     */
    private fun acceptPending(udi: String) {
        NmpaCache.putOverride(udi, vm.bufProduct, vm.bufSpec, vm.bufCompany)
        vm.bufNmpaState = "local"
        vm.updateAllByUdi(udi, vm.bufProduct, vm.bufSpec, vm.bufCompany)
        updateBufferUi()
        toast(R.string.toast_accepted_pending)
    }

    private fun showEditDialog(
        udi: String, defName: String?, defSpec: String?, defCompany: String?,
        onSaved: (String?, String?, String?) -> Unit = { _, _, _ -> }
    ) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_product, null)
        // 扫码页编辑「自定义字典」：仅名称/型号/厂家，隐藏清单专属字段组
        view.findViewById<android.view.View>(R.id.group_list_fields)?.visibility = View.GONE
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
                if (vm.bufUdi == udi) {
                    vm.bufProduct = name ?: vm.bufProduct
                    vm.bufSpec = spec ?: vm.bufSpec
                    vm.bufCompany = company ?: vm.bufCompany
                    if (vm.bufNmpaState == "skip" || vm.bufNmpaState == "err" || vm.bufNmpaState == "none") {
                        vm.bufNmpaState = "local"
                    }
                    updateBufferUi()
                }
                onSaved(name, spec, company)
                toast(R.string.toast_saved)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ——— 相机权限 ———
    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), camPerm) == PackageManager.PERMISSION_GRANTED) {
            scanner.resume()
        } else {
            // 用 Fragment 自带的 requestPermissions，结果回传本 Fragment 的 onRequestPermissionsResult
            requestPermissions(arrayOf(camPerm), rcCam)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == rcCam && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanner.resume()
        } else if (requestCode == rcCam) {
            toast(R.string.toast_no_camera)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
