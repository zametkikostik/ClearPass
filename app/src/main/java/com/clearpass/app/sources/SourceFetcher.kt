package com.clearpass.app.sources

import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SourceFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val LINK = Pattern.compile(
        """(vless|hysteria2|hy2|tuic|trojan|ss|vmess)://[^\s<>\"']+""",
        Pattern.CASE_INSENSITIVE
    )

    suspend fun fetchLinks(urls: List<String>): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        for (url in urls) {
            try {
                LogCollector.i("Source", "GET $url")
                val body = download(url) ?: continue
                val text = tryBase64(body)
                val m = LINK.matcher(text)
                var n = 0
                while (m.find()) {
                    found += m.group().trim()
                    n++
                }
                if (n > 0) {
                    LogCollector.i("Source", "OK $n links from $url")
                    break
                } else {
                    LogCollector.w("Source", "No links in response")
                }
            } catch (e: Exception) {
                LogCollector.w("Source", "${e.message}")
            }
        }
        found.toList()
    }

    suspend fun fetchSource(source: SafeSources.Source): List<String> =
        fetchLinks(source.urls)

    private fun download(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ClearPass/0.2")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogCollector.w("Source", "HTTP ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }

    private fun tryBase64(raw: String): String {
        val cleaned = raw.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        return try {
            val decoded = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
    }
}
