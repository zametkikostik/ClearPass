package com.clearpass.app.tester

import com.clearpass.app.parser.ProxyLink
import com.clearpass.app.parser.ProxyLinkParser
import com.clearpass.app.util.LogCollector
import com.clearpass.app.whitelist.WhiteListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

data class TestedLink(
    val uri: String,
    val address: String,
    val port: Int,
    val sni: String,
    val protocol: String,
    val latencyMs: Int,
    val score: Int
)

object ConfigTester {

    suspend fun testLinks(
        rawLinks: List<String>,
        maxToTest: Int = 20
    ): List<TestedLink> = withContext(Dispatchers.IO) {
        val candidates = rawLinks
            .distinct()
            .shuffled()
            .take(maxToTest)
            .mapNotNull { raw ->
                val injected = when {
                    raw.startsWith("vless://", true) ->
                        WhiteListManager.injectSni(raw) ?: raw
                    else -> raw
                }
                ProxyLinkParser.parse(injected)?.let { injected to it }
            }

        LogCollector.i("Tester", "Testing ${candidates.size} links")

        val results = candidates.map { (uri, link) ->
            async { testOne(uri, link) }
        }.awaitAll().filterNotNull().sortedBy { it.latencyMs }

        LogCollector.i("Tester", "Alive: ${results.size}")
        results
    }

    private suspend fun testOne(uri: String, link: ProxyLink): TestedLink? {
        return try {
            val ok = withTimeoutOrNull(2500L) {
                Socket().use { s ->
                    s.connect(InetSocketAddress(link.address, link.port), 2000)
                    true
                }
            } ?: false
            if (!ok) return null

            val start = System.currentTimeMillis()
            val second = withTimeoutOrNull(2000L) {
                Socket().use { s ->
                    s.connect(InetSocketAddress(link.address, link.port), 1800)
                    true
                }
            } ?: false
            if (!second) return null
            val latency = (System.currentTimeMillis() - start).toInt()

            val sni = when (link) {
                is ProxyLink.Vless -> link.sni ?: "—"
                is ProxyLink.Hysteria2 -> link.sni ?: "—"
                is ProxyLink.Tuic -> link.sni ?: "—"
            }
            val protocol = when (link) {
                is ProxyLink.Vless -> "VLESS"
                is ProxyLink.Hysteria2 -> "Hysteria2"
                is ProxyLink.Tuic -> "TUIC"
            }
            val score = when {
                latency < 80 -> 95
                latency < 150 -> 80
                latency < 300 -> 65
                else -> 45
            }
            TestedLink(uri, link.address, link.port, sni, protocol, latency, score)
        } catch (_: Exception) {
            null
        }
    }
}
