package com.hospital.udiscan

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 直连 NMPA UDI 数据库后端接口，无需登录、无需浏览器。
 * 正确语法：searchType=1 + query=纯14位UDI（跨字段模糊匹配）。
 * 注意：primaryDeviceId 只是返回字段，不能作搜索条件（会 500）。
 * 返回三态：ok（primaryDeviceId 精确等于 UDI）/ pending（命中但需人工核对）/ skip（无记录）/ err（网络失败）。
 */
object NmpaClient {

    private const val TAG = "NmpaClient"
    private const val ENDPOINT = "https://udi.nmpa.gov.cn/getDeviceList.html"

    data class NmpaResult(
        val state: String,            // ok | pending | skip | err
        val productName: String?,
        val specification: String?,
        val companyName: String?
    )

    private fun trustAllSsl(): SSLContext {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx
    }

    fun query(udi: String): NmpaResult {
        return try {
            val pure = udi.trim()
            val body = buildString {
                append("query=").append(URLEncoder.encode(pure, "UTF-8"))
                append("&searchType=1")
                append("&_search=false")
                append("&nd=").append(System.currentTimeMillis())
                append("&rows=20&page=1&sidx=&sord=asc")
            }
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                val ssl = trustAllSsl()
                conn.sslSocketFactory = ssl.socketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Referer", "https://udi.nmpa.gov.cn/")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
            conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return NmpaResult("err", null, null, null)
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            parse(text, pure)
        } catch (e: Exception) {
            Log.e(TAG, "query failed: $udi", e)
            NmpaResult("err", null, null, null)
        }
    }

    private fun parse(json: String, udi: String): NmpaResult {
        return try {
            val root = JSONObject(json)
            val rows = root.optJSONArray("rows") ?: JSONArray()
            if (rows.length() == 0) return NmpaResult("skip", null, null, null)
            // 精确匹配 primaryDeviceId
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("primaryDeviceId", "") == udi) {
                    return NmpaResult(
                        "ok",
                        row.optString("productName", "").takeIf { it.isNotEmpty() },
                        row.optString("specification", "").takeIf { it.isNotEmpty() },
                        row.optString("companyName", "").takeIf { it.isNotEmpty() }
                    )
                }
            }
            // 非精确命中：取首条作待核对
            val row = rows.getJSONObject(0)
            NmpaResult(
                "pending",
                row.optString("productName", "").takeIf { it.isNotEmpty() },
                row.optString("specification", "").takeIf { it.isNotEmpty() },
                row.optString("companyName", "").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "parse failed", e)
            NmpaResult("err", null, null, null)
        }
    }
}
