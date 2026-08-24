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
import java.util.UUID

/**
 * 扫码页（ViewPager2 第 0 页）：取景 + 「本次将录入」预览卡 + 当前待录入缓冲卡。
 * 所有状态读写都经过 ScanViewModel，滑到清单页再滑回不丢失。
 * 相机生命周期随 Fragment：滑走 onPause 暂停，滑回 onResume 恢复。
 */
class ScanFragment : Fragment() {

    private lateinit var vm: ScanViewModel
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var tvBuffer: TextView
    private lateinit var tvProduct: TextView
    private lateinit var tvQty: TextView
    private lateinit var btnPlus: Button
    private lateinit var btnMinus: Button
    private lateinit var btnQuery: Button
    private lateinit var btnAdd: Button
    private lateinit var btnDiscard: Button
    private lateinit var scanFlash: TextView
    private lateinit var previewChips: LinearLayout
    private lateinit var tvPreviewQty: TextView
    private lateinit var tvPreviewHint: TextView

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
        tvBuffer = view.findViewById(R.id.tv_buffer)
        tvProduct = view.findViewById(R.id.tv_product)
        tvQty = view.findViewById(R.id.tv_qty)
        btnPlus = view.findViewById(R.id.btn_plus)
        btnMinus = view.findViewById(R.id.btn_minus)
        btnQuery = view.findViewById(R.id.btn_query)
        btnAdd = view.findViewById(R.id.btn_add)
        btnDiscard = view.findViewById(R.id.btn_discard)
        scanFlash = view.findViewById(R.id.scan_flash)
        previewChips = view.findViewById(R.id.preview_chips)
        tvPreviewQty = view.findViewById(R.id.tv_preview_qty)
        tvPreviewHint = view.findViewById(R.id.tv_preview_hint)

        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                onScanned(text)
            }

            override fun possibleResultPoints(points: List<com.google.zxing.ResultPoint>) {}
        })

        btnPlus.setOnClickListener { vm.bufQty++; updateBufferUi() }
        btnMinus.setOnClickListener { if (vm.bufQty > 1) vm.bufQty--; updateBufferUi() }
        btnQuery.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) queryNmpa(udi) else toast(R.string.toast_no_udi)
        }
        btnAdd.setOnClickListener { commitBuffer() }
        btnDiscard.setOnClickListener { vm.clearBuffer() }

        tvProduct.setOnClickListener {
            val udi = vm.bufUdi
            if (!udi.isNullOrEmpty()) editCurrentProduct(udi)
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
    private fun onScanned(text: String) {
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
        appendField(b, "UDI(01)", vm.bufUdi, true)
        appendField(b, "批号(10)", vm.bufBatch)
        appendField(b, "效期(17)", Gs1Parser.formatDateYYMMDD(vm.bufExpiry) ?: vm.bufExpiry)
        appendField(b, "生产(11)", Gs1Parser.formatDateYYMMDD(vm.bufProduction) ?: vm.bufProduction)
        appendField(b, "序列(${vm.bufSerialAi ?: "?"}", vm.bufSerial)
        if (vm.bufPendingUnknown.isNotEmpty()) {
            b.append("\n⚠ 待确认：").append(vm.bufPendingUnknown.joinToString(" / "))
        }
        tvBuffer.text = b.toString()
        tvProduct.text = when (vm.bufNmpaState) {
            "ok" -> "✓ ${vm.bufProduct ?: ""}"
            "local" -> "✎本地字典：${vm.bufProduct ?: ""}"
            "pending" -> "⚠ 待核对：${vm.bufProduct ?: ""}"
            "skip" -> "✗ NMPA 无记录"
            "err" -> "✗ 查询失败"
            "querying" -> "… 查询中"
            else -> vm.bufProduct ?: ""
        }
        tvQty.text = vm.bufQty.toString()
        updatePreviewCard()
    }

    /** 更新「本次将录入」预览卡。 */
    private fun updatePreviewCard() {
        previewChips.removeAllViews()
        val chips = mutableListOf<Pair<String, String?>>()
        chips.add(getString(R.string.chip_udi) to vm.bufUdi)
        chips.add(getString(R.string.chip_batch) to vm.bufBatch)
        chips.add(getString(R.string.chip_expiry) to (Gs1Parser.formatDateYYMMDD(vm.bufExpiry) ?: vm.bufExpiry))
        chips.add(getString(R.string.chip_prod) to (Gs1Parser.formatDateYYMMDD(vm.bufProduction) ?: vm.bufProduction))
        chips.add(getString(R.string.chip_serial) to vm.bufSerial)

        var anyFilled = false
        for ((label, value) in chips) {
            if (value.isNullOrEmpty()) continue
            anyFilled = true
            val chip = TextView(requireContext()).apply {
                text = "$label: $value"
                setPadding(12, 6, 12, 6)
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                background = ContextCompat.getDrawable(requireContext(), android.R.color.holo_blue_dark)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 8, 8)
                layoutParams = lp
            }
            previewChips.addView(chip)
        }
        previewChips.visibility = if (anyFilled) View.VISIBLE else View.GONE

        tvPreviewQty.text = getString(R.string.preview_qty, vm.bufQty)

        if (vm.bufPendingUnknown.isNotEmpty()) {
            for (vv in vm.bufPendingUnknown) {
                val chip = TextView(requireContext()).apply {
                    text = "${getString(R.string.chip_unknown)}: $vv"
                    setPadding(12, 6, 12, 6)
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                    background = ContextCompat.getDrawable(requireContext(), android.R.color.holo_orange_dark)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 8, 8)
                    layoutParams = lp
                    setOnClickListener { assignUnknown(vv) }
                }
                previewChips.addView(chip)
            }
            tvPreviewHint.text = getString(R.string.preview_hint_pending, vm.bufPendingUnknown.joinToString(" / "))
            tvPreviewHint.visibility = View.VISIBLE
        } else {
            tvPreviewHint.text = getString(R.string.preview_hint_empty)
            tvPreviewHint.visibility = if (anyFilled) View.GONE else View.VISIBLE
        }
    }

    /** 对待确认段手动指定字段类型（点 chip 触发）。 */
    private fun assignUnknown(value: String) {
        val options = arrayOf(
            getString(R.string.assign_udi),
            getString(R.string.assign_batch),
            getString(R.string.assign_expiry),
            getString(R.string.assign_prod),
            getString(R.string.assign_serial)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_assign_title))
            .setMessage("原始内容：$value")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (vm.bufUdi == null) vm.bufUdi = value
                    1 -> if (vm.bufBatch == null) vm.bufBatch = value
                    2 -> if (vm.bufExpiry == null) vm.bufExpiry = value
                    3 -> if (vm.bufProduction == null) vm.bufProduction = value
                    4 -> if (vm.bufSerial == null) { vm.bufSerial = value; vm.bufSerialAi = "21" }
                }
                vm.bufPendingUnknown = vm.bufPendingUnknown.filter { it != value }
                updateBufferUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appendField(b: StringBuilder, label: String, value: String?, strong: Boolean = false) {
        val v = value ?: "—"
        if (strong) b.append("► $label: $v\n") else b.append("  $label: $v\n")
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
                if (net.state != "err") NmpaCache.put(udi, net)
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

    // ——— 编辑当前缓冲产品（写入 override）———
    private fun editCurrentProduct(udi: String) {
        val existing = NmpaCache.getOverride(udi) ?: NmpaCache.get(udi)
        showEditDialog(udi, existing?.productName, existing?.specification, existing?.companyName)
    }

    private fun showEditDialog(udi: String, defName: String?, defSpec: String?, defCompany: String?) {
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
                if (vm.bufUdi == udi) {
                    vm.bufProduct = name ?: vm.bufProduct
                    vm.bufSpec = spec ?: vm.bufSpec
                    vm.bufCompany = company ?: vm.bufCompany
                    if (vm.bufNmpaState == "skip" || vm.bufNmpaState == "err" || vm.bufNmpaState == "none") {
                        vm.bufNmpaState = "local"
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
