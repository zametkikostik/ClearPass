package com.clearpass.app.sources

import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        .retryOnConnectionFailure(true)
        .build()

    private val LINK = Pattern.compile(
        """(vless|hysteria2|hy2|tuic|trojan|ss|vmess)://[^\s<>"']+""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Параллельная загрузка всех URL с дедупликацией.
     * Пробует все зеркала источника для максимального охвата.
     */
    suspend fun fetchLinks(urls: List<String>): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        
        // Загружаем все URL параллельно
        val jobs = urls.map { url ->
            async {
                try {
                    LogCollector.i("Source", "GET $url")
                    val body = download(url) ?: return@async emptyList<String>()
                    val text = tryBase64(body)
                    extractLinks(text)
                } catch (e: Exception) {
                    LogCollector.w("Source", "${e.message} for $url")
                    emptyList<String>()
                }
            }
        }
        
        // Собираем результаты со всех зеркал
        val results = jobs.awaitAll()
        results.forEach { links ->
            if (links.isNotEmpty()) {
                found.addAll(links)
                LogCollector.i("Source", "OK ${links.size} links from batch")
            }
        }
        
        found.toList()
    }

    private fun extractLinks(text: String): List<String> {
        val result = mutableListOf<String>()
        val m = LINK.matcher(text)
        while (m.find()) {
            result += m.group().trim()
        }
        return result
    }

    suspend fun fetchSource(source: SafeSources.Source): List<String> =
        fetchLinks(source.urls)

    /**
     * Загрузка с приоритетом по порядку URL (первый успешный не прерывает остальные).
     * Это позволяет собрать конфиги со всех доступных зеркал.
     */
    suspend fun fetchSourcePrioritized(source: SafeSources.Source): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        
        // Сортируем URL по приоритету: GitLab → jsDelivr → GitHub Raw
        val sortedUrls = source.urls.sortedWith(compareBy { url ->
            when {
                url.contains("gitlab.com") -> 0
                url.contains("cdn.jsdelivr.net") -> 1
                url.contains("raw.githack.com") -> 2
                url.contains("codeberg.org") -> 3
                url.contains("bitbucket.org") -> 4
                url.contains("raw.githubusercontent.com") -> 5
                else -> 6
            }
        })
        
        for (url in sortedUrls) {
            try {
                LogCollector.i("Source", "GET $url")
                val body = download(url) ?: continue
                val text = tryBase64(body)
                val links = extractLinks(text)
                if (links.isNotEmpty()) {
                    found.addAll(links)
                    LogCollector.i("Source", "OK ${links.size} links from $url")
                } else {
                    LogCollector.w("Source", "No links in response from $url")
                }
            } catch (e: Exception) {
                LogCollector.w("Source", "${e.message} for $url")
            }
        }
        
        found.toList()
    }

    private fun download(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ClearPass/0.2 (Android VPN Client)")
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogCollector.w("Source", "HTTP ${resp.code} for $url")
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
