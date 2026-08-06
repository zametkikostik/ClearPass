package com.clearpass.app.vpn

import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

object HealthMonitor {

    suspend fun isServerReachable(host: String, port: Int, timeoutMs: Int = 2500): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeoutMs.toLong() + 200) {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), timeoutMs)
                        true
                    }
                } ?: false
            } catch (e: Exception) {
                LogCollector.d("Health", "unreachable: ${e.message}")
                false
            }
        }
}
