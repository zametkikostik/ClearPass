package com.clearpass.app.whitelist

import android.net.Uri
import com.clearpass.app.util.LogCollector
import kotlin.random.Random

object WhiteListManager {

    private val defaultWhitelist = listOf(
        "gosuslugi.ru", "mos.ru", "sberbank.ru", "vtb.ru", "alfabank.ru",
        "tinkoff.ru", "yandex.ru", "mail.ru", "vk.com", "ok.ru",
        "rbc.ru", "ria.ru", "tass.ru", "cbr.ru", "nalog.gov.ru",
        "pfr.gov.ru", "minzdrav.gov.ru", "edu.gov.ru", "culture.gov.ru", "mvd.ru"
    )

    @Volatile
    var whitelist: List<String> = defaultWhitelist
        private set

    fun updateWhitelist(newList: List<String>) {
        whitelist = newList.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        LogCollector.i("WL", "Whitelist updated: ${whitelist.size} domains")
    }

    fun randomSni(): String {
        return if (whitelist.isEmpty()) "yandex.ru" else whitelist.random(Random)
    }

    fun injectSni(vlessUri: String, forcedSni: String? = null): String? {
        return try {
            val uri = Uri.parse(vlessUri.trim())
            if (uri.scheme?.equals("vless", ignoreCase = true) != true) return null

            val newSni = forcedSni ?: randomSni()
            val params = mutableMapOf<String, String>()
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { params[key] = it }
            }
            params["sni"] = newSni

            val query = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val userInfo = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val fragment = uri.fragment?.let { "#$it" } ?: ""

            "vless://$userInfo@$host:$port?$query$fragment"
        } catch (e: Exception) {
            LogCollector.e("WL", "injectSni failed: ${e.message}")
            null
        }
    }
}
