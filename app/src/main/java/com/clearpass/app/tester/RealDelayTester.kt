package com.clearpass.app.tester

import android.content.Context
import com.clearpass.app.converter.SingBoxConverter
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.parser.ProxyLink
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Честный real-delay тест конфига: поднимаем отдельный sing-box с локальным
 * SOCKS на случайном порту и меряем полный HTTP round-trip через туннель
 * (GET http://cp.cloudflare.com/generate_204).
 *
 * Отдельный процесс нужен, чтобы не трогать боевой sing-box (другие порты,\ * без clash_api). raw Socket вместо HttpURLConnection — в манифесте
 * cleartext запрещён, а нам нужен именно HTTP/80 через SOCKS.
 */
object RealDelayTester {

    private const val PROBE_HOST = "cp.cloudflare.com"
    private const val PROBE_PATH = "/generate_204"
    private const val CORE_START_TIMEOUT = 3000L
    private const val REQUEST_TIMEOUT = 5000L

    /**
     * Возвращает честную задержку (мс) до 204 через туннель или null,
     * если конфиг не работает / ядро недоступно.
     */
    suspend fun measureDelay(context: Context, link: ProxyLink): Int? =
        withContext(Dispatchers.IO) {
            val bin = CoreBootstrap.resolveBinary(context) ?: return@withContext null
            val port = findFreePort()
            val auth = SingBoxConverter.generateLocalAuth()
            val conf = File(context.cacheDir, "cp-test-$port.json")

            var process: Process? = null
            try {
                conf.writeText(SingBoxConverter.convertForTest(link, auth, port))

                val pb = ProcessBuilder(
                    bin.absolutePath, "run",
                    "-c", conf.absolutePath,
                    "-D", context.filesDir.absolutePath
                )
                pb.redirectErrorStream(true)
                pb.directory(context.filesDir)
                pb.environment()["HOME"] = context.filesDir.absolutePath
                process = pb.start()

                // Ждём, пока локальный SOCKS начнёт принимать соединения
                if (!waitForLocalPort(port, CORE_START_TIMEOUT)) {
                    LogCollector.d("RealDelay", "core did not start on :$port")
                    return@withContext null
                }

                val start = System.currentTimeMillis()
                val code = withTimeoutOrNull(REQUEST_TIMEOUT) {
                    socksHttpProbe(port, auth)
                }
                val latency = (System.currentTimeMillis() - start).toInt()

                if (code == 204 || code == 200) latency else null
            } catch (e: Exception) {
                LogCollector.d("RealDelay", "${e.message}")
                null
            } finally {
                try {
                    process?.let { p ->
                        p.destroy()
                        if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                            p.destroyForcibly()
                        }
                    }
                } catch (_: Exception) {
                }
                try { conf.delete() } catch (_: Exception) {}
            }
        }

    /** SOCKS5 handshake (RFC 1928 + 1929) + HTTP GET /generate_204. */
    private fun socksHttpProbe(port: Int, auth: SingBoxConverter.LocalAuth): Int {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 3000)
            s.soTimeout = 4000

            val out = s.getOutputStream()
            val input = s.getInputStream()

            // 1. Приветствие: предлагаем username/password auth
            out.write(byteArrayOf(0x05, 0x01, 0x02))
            out.flush()
            val greeting = readExact(input, 2)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x02.toByte()) {
                throw IOException("SOCKS5 greeting mismatch")
            }

            // 2. Авторизация (RFC 1929)
            val u = auth.username.toByteArray(Charsets.UTF_8)
            val p = auth.password.toByteArray(Charsets.UTF_8)
            val authReq = ByteArray(3 + u.size + p.size)
            authReq[0] = 0x01
            authReq[1] = u.size.toByte()
            System.arraycopy(u, 0, authReq, 2, u.size)
            authReq[2 + u.size] = p.size.toByte()
            System.arraycopy(p, 0, authReq, 3 + u.size, p.size)
            out.write(authReq)
            out.flush()
            val authResp = readExact(input, 2)
            if (authResp[1] != 0x00.toByte()) throw IOException("SOCKS5 auth rejected")

            // 3. CONNECT к cp.cloudflare.com:80 (домен передаём как есть —
            //    резолвится на стороне прокси)
            val host = PROBE_HOST.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + host.size)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = host.size.toByte()
            System.arraycopy(host, 0, req, 5, host.size)
            req[5 + host.size] = 0x00  // порт 80, старший байт
            req[6 + host.size] = 0x50  // порт 80, младший байт
            out.write(req)
            out.flush()

            // 4. Ответ: VER REP RSV ATYP + адрес + порт
            val resp = readExact(input, 4)
            if (resp[1] != 0x00.toByte()) throw IOException("SOCKS5 connect code=${resp[1]}")
            when (resp[3]) {
                0x01.toByte() -> readExact(input, 6)    // IPv4 + порт
                0x04.toByte() -> readExact(input, 18)   // IPv6 + порт
                0x03.toByte() -> {
                    val len = readExact(input, 1)[0].toInt() and 0xFF
                    readExact(input, len + 2)
                }
            }

            // 5. HTTP GET через туннель (raw socket — cleartext-политика
            //    на plain Socket не действует)
            val http = "GET $PROBE_PATH HTTP/1.1\r\n" +
                "Host: $PROBE_HOST\r\n" +
                "User-Agent: ClearPass/0.3\r\n" +
                "Connection: close\r\n\r\n"
            out.write(http.toByteArray(Charsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: throw IOException("empty HTTP response")
            return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw IOException("SOCKS5 stream EOF")
            read += r
        }
        return buf
    }

    private suspend fun waitForLocalPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 200) }
                return true
            } catch (_: Exception) {
                delay(80)
            }
        }
        return false
    }

    private fun findFreePort(): Int {
        return try {
            ServerSocket().use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress("127.0.0.1", 0))
                s.localPort
            }
        } catch (_: Exception) {
            20000 + Random.nextInt(20000)
        }
    }
}
