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

    private val rcImportCustom = 2001
    private val rcImportNmpa = 2002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.menu_export_list).setOnClickListener { exportList() }
        findViewById<android.view.View>(R.id.menu_export_nmpa).setOnClickListener { exportNmpa() }
        findViewById<android.view.View>(R.id.menu_export_custom).setOnClickListener { exportCustom() }
        findViewById<android.view.View>(R.id.menu_import_custom).setOnClickListener { importFile(rcImportCustom) }
        findViewById<android.view.View>(R.id.menu_import_nmpa).setOnClickListener { importFile(rcImportNmpa) }
        findViewById<android.view.View>(R.id.menu_dict).setOnClickListener {
            startActivity(Intent(this, DictActivity::class.java))
        }
    }

    // ——— 导出清单（JSON）———
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
        file.writeText(arr.toString(1), Charsets.UTF_8)
        shareFile(file, "application/json")
    }

    // ——— 导出自定义字典（override）———
    private fun exportCustom() {
        val json = NmpaCache.exportOverridesJson()
        if (json == "[]") { toast(getString(R.string.toast_dict_empty)); return }
        val file = File(exportDir(), "udi_overrides_${System.currentTimeMillis()}.json")
        file.writeText(json, Charsets.UTF_8)
        shareFile(file, "application/json")
        toast(getString(R.string.toast_exported))
    }

    // ——— 导出 NMPA 官方缓存 ———
    private fun exportNmpa() {
        val json = NmpaCache.exportCacheJson()
        if (json == "[]") { toast(getString(R.string.toast_dict_empty)); return }
        val file = File(exportDir(), "udi_nmpa_cache_${System.currentTimeMillis()}.json")
        file.writeText(json, Charsets.UTF_8)
        shareFile(file, "application/json")
        toast(getString(R.string.toast_exported))
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

    // ——— 导入（自定义 / NMPA），由 requestCode 区分 ———
    private fun importFile(which: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(Intent.createChooser(intent, "选择 JSON 文件"), which)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        val isNmpa = requestCode == rcImportNmpa
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
                if (isNmpa) {
                    val list = mutableListOf<NmpaCache.CacheEntry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(NmpaCache.CacheEntry(
                            udi = o.optString("udi", ""),
                            name = o.optString("name", "").let { if (it.isEmpty()) null else it },
                            spec = o.optString("spec", "").let { if (it.isEmpty()) null else it },
                            company = o.optString("company", "").let { if (it.isEmpty()) null else it },
                            state = o.optString("state", "ok").let { if (it.isEmpty()) "ok" else it }
                        ))
                    }
                    n = NmpaCache.importCache(list)
                } else {
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
                }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.toast_import_err)) }
                return@Thread
            }
            val where = if (isNmpa) "NMPA" else "自定义"
            runOnUiThread { toast("已导入 $where 字典 $n 条") }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
