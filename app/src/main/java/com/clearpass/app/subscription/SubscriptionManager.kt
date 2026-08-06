package com.clearpass.app.subscription

import android.util.Base64
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SubscriptionManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val LINK = Pattern.compile(
        """(vless|hysteria2|hy2|tuic|trojan|ss|vmess)://[^\s<>\"']+""",
        Pattern.CASE_INSENSITIVE
    )

    suspend fun fetchFromSubscriptions(urls: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val found = linkedSetOf<String>()
            for (url in urls) {
                try {
                    LogCollector.i("Sub", "GET $url")
                    val body = download(url) ?: continue
                    val text = tryBase64(body)
                    val m = LINK.matcher(text)
                    while (m.find()) found += m.group().trim()
                } catch (e: Exception) {
                    LogCollector.w("Sub", "${e.message}")
                }
            }
            LogCollector.i("Sub", "Extracted ${found.size} links")
            found.toList()
        }

    private fun download(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "ClearPass/0.2").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun tryBase64(raw: String): String {
        val cleaned = raw.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        return try {
            String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
    }
}
