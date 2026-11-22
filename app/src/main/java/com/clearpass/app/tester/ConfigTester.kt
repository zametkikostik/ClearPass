package com.clearpass.app.tester

import android.content.Context
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.parser.ProxyLink
import com.clearpass.app.parser.ProxyLinkParser
import com.clearpass.app.util.LogCollector
import com.clearpass.app.whitelist.WhiteListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private const val DEEP_TEST_TOP = 8       // сколько лучших проверяем честно
    private const val DEEP_CONCURRENCY = 3    // параллельных sing-box процессов

    /**
     * Двухфазный тест:
     *  1) быстрый TCP-ping — отсеивает мёртвые порты;
     *  2) если передан deepContext и есть ядро — real-delay через локальный
     *     SOCKS (полный HTTP round-trip). Ссылки, которые держат TCP, но
     *     не водят трафик, вылетают.
     */
    suspend fun testLinks(
        rawLinks: List<String>,
        maxToTest: Int = 20,
        deepContext: Context? = null
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

        // Фаза 1: быстрый TCP-фильтр
        val tcpAlive = candidates.map { (uri, link) ->
            async { tcpProbe(uri, link) }
        }.awaitAll().filterNotNull().sortedBy { it.latencyMs }

        LogCollector.i("Tester", "TCP alive: ${tcpAlive.size}")

        // Фаза 2: честный real-delay (если есть ядро)
        val result = if (deepContext != null && CoreBootstrap.hasBinary(deepContext)) {
            runDeepTest(deepContext, candidates, tcpAlive)
        } else {
            tcpAlive
        }

        LogCollector.i("Tester", "Alive: ${result.size}")
        result
    }

    /**
     * Deep-тест топа TCP-выживших. Если не прошёл ни один — подозреваем
     * инфраструктуру (ядро не стартует и т.п.) и оставляем TCP-результаты,
     * чтобы не остаться вообще без конфигов.
     */
    private suspend fun runDeepTest(
        context: Context,
        candidates: List<Pair<String, ProxyLink>>,
        tcpAlive: List<TestedLink>
    ): List<TestedLink> = withContext(Dispatchers.IO) {
        val byUri = candidates.toMap()
        val top = tcpAlive.take(DEEP_TEST_TOP)
        if (top.isEmpty()) return@withContext tcpAlive

        val semaphore = Semaphore(DEEP_CONCURRENCY)
        val deep = top.map { tcp ->
            async {
                val link = byUri[tcp.uri] ?: return@async null
                semaphore.withPermit {
                    val delayMs = RealDelayTester.measureDelay(context, link)
                    if (delayMs != null) {
                        LogCollector.i("RealDelay", "${tcp.address}: $delayMs ms")
                        deepResult(tcp, delayMs)
                    } else {
                        null
                    }
                }
            }
        }.awaitAll()

        val verified = deep.filterNotNull()
        val testedUris = top.map { it.uri }.toSet()

        if (verified.isEmpty()) {
            LogCollector.w("Tester", "Deep test: 0/${top.size} passed — keeping TCP results")
            return@withContext tcpAlive
        }

        val rest = tcpAlive.filter { it.uri !in testedUris }
        LogCollector.i(
            "Tester",
            "Deep test: ${verified.size}/${top.size} verified, " +
                "dropped ${testedUris.size - verified.size}"
        )
        (verified + rest).sortedWith(
            compareByDescending<TestedLink> { it.score }.thenBy { it.latencyMs }
        )
    }

    /**
     * Честная задержка всегда больше TCP-ping, поэтому шкала своя:
     * проверенный сквозным трафиком конфиг ранжируем выше «живого порта».
     */
    private fun deepResult(tcp: TestedLink, delayMs: Int): TestedLink {
        val score = when {
            delayMs < 600 -> 98
            delayMs < 1200 -> 92
            delayMs < 2000 -> 85
            else -> 75
        }
        return tcp.copy(latencyMs = delayMs, score = score)
    }

    private suspend fun tcpProbe(uri: String, link: ProxyLink): TestedLink? {
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
