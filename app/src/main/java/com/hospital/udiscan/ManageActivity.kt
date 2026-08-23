package com.hospital.udiscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

class ManageActivity : AppCompatActivity() {

    private val rcImport = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.menu_export).setOnClickListener { exportList() }
        findViewById<android.view.View>(R.id.menu_import).setOnClickListener { importOverrides() }
        findViewById<android.view.View>(R.id.menu_dict).setOnClickListener { openDict() }
        findViewById<android.view.View>(R.id.menu_clear).setOnClickListener { clearList() }
    }

    // ——— 导出清单（JSON / CSV 系统分享）———
    private fun exportDir(): File {
        val d = File(filesDir, "exports")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun exportList() {
        if (ScanStore.items.isEmpty()) { toast(getString(R.string.toast_list_empty)); return }
        val arr = JSONArray()
        for (it in ScanStore.items) {
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

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 / 分享"))
        toast(getString(R.string.toast_exported))
    }

    // ——— 导入 / 导出 UDI 覆盖字典（多设备同步）———
    private fun openDict() {
        val msg = "本地覆盖字典：${NmpaCache.overrideCount()} 条\n" +
                "NMPA 官方缓存：${NmpaCache.count()} 条\n\n" +
                "导出：把覆盖字典存成 udi_overrides.json，通过微信/网盘发给其他设备。\n" +
                "导入：选择其他设备发来的 udi_overrides.json，合并到本机。"
        AlertDialog.Builder(this)
            .setTitle("产品字典")
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
        toast(getString(R.string.toast_exported))
    }

    private fun importOverrides() {
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
                    runOnUiThread { toast(getString(R.string.toast_import_err)) }
                    return@Thread
                }
                runOnUiThread { toast(getString(R.string.toast_imported, n)) }
            }.start()
        }
    }

    private fun clearList() {
        if (ScanStore.items.isEmpty()) { toast(getString(R.string.toast_dict_empty)); return }
        AlertDialog.Builder(this)
            .setTitle("清空清单")
            .setMessage("确定移除本次已录入的全部条目？此操作不可撤销。")
            .setPositiveButton("清空") { _, _ ->
                ScanStore.clear()
                toast(getString(R.string.toast_clear_done))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
