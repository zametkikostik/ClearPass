package com.clearpass.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.clearpass.app.MainActivity
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.data.SettingsStore
import com.clearpass.app.data.SplitTunnelMode
import com.clearpass.app.tunnel.Tun2SocksBridge
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class ClearPassVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var killSwitch = true
    private var userStop = false
    private var socksUser: String = ""
    private var socksPass: String = ""
    private var currentHost: String? = null
    private var currentPort: Int = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val ACTION_CONNECT = "com.clearpass.CONNECT"
        const val ACTION_DISCONNECT = "com.clearpass.DISCONNECT"
        const val EXTRA_CONFIG = "config_json"
        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val ACTION_CORE_DIED = "com.clearpass.CORE_DIED"
        const val EXTRA_SOCKS_USER = "socks_user"
        const val EXTRA_SOCKS_PASS = "socks_pass"
        const val EXTRA_HOST = "proxy_host"
        const val EXTRA_PORT = "proxy_port"
        private const val NOTIF_ID = 42
        private const val CHANNEL = "clearpass_vpn"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                userStop = false
                killSwitch = intent.getBooleanExtra(EXTRA_KILL_SWITCH, true)
                socksUser = intent.getStringExtra(EXTRA_SOCKS_USER) ?: ""
                socksPass = intent.getStringExtra(EXTRA_SOCKS_PASS) ?: ""
                currentHost = intent.getStringExtra(EXTRA_HOST)
                currentPort = intent.getIntExtra(EXTRA_PORT, 0)
                val config = intent.getStringExtra(EXTRA_CONFIG)
                startForeground(NOTIF_ID, notification("Connecting…"))
                scope.launch { startTunnel(config) }
            }
            ACTION_DISCONNECT -> {
                userStop = true
                stopTunnel(true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun startTunnel(configJson: String?) {
        try {
            if (configJson.isNullOrBlank()) {
                LogCollector.e("VPN", "Empty config")
                updateNotif("Error: empty config")
                if (!killSwitch) stopSelf()
                return
            }

            CoreBootstrap.prepare(this)
            val configFile = File(filesDir, "singbox.json")
            FileOutputStream(configFile).use { it.write(configJson.toByteArray(Charsets.UTF_8)) }

            val builder = Builder()
                .setSession("ClearPass")
                .addAddress("10.10.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setBlocking(true)

            // Split tunneling: применяем режим и список пакетов из настроек
            applySplitTunneling(builder)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                LogCollector.e("VPN", "Failed to establish TUN")
                updateNotif("Error: VPN permission")
                stopSelf()
                return
            }

            LogCollector.i("VPN", "TUN established")

            val bin = CoreBootstrap.resolveBinary(this)
            if (bin == null) {
                LogCollector.w("VPN", "libsingbox.so not found — SAFE MODE")
                updateNotif("Safe mode: core missing")
                return
            }

            try {
                bin.setReadable(true)
                bin.setExecutable(true)
            } catch (e: Exception) {
                LogCollector.w("VPN", "chmod: ${e.message}")
            }

            val pb = ProcessBuilder(
                bin.absolutePath, "run",
                "-c", configFile.absolutePath,
                "-D", filesDir.absolutePath
            )
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = filesDir.absolutePath

            coreProcess = pb.start()
            LogCollector.i("VPN", "sing-box process started")
            updateNotif("Connected")

            // Следим за сменой сети (Wi-Fi ↔ LTE): проверяем сервер и при
            // недоступности отправляем CORE_DIED — ConnectionManager ротирует
            watchNetwork()

            scope.launch {
                delay(1200)
                if (userStop) return@launch
                val fd = try { vpnInterface?.fd ?: -1 } catch (_: Exception) { -1 }
                if (socksUser.isNotBlank() && socksPass.isNotBlank()) {
                    Tun2SocksBridge.start(
                        this@ClearPassVpnService, fd,
                        socksUser = socksUser, socksPass = socksPass
                    )
                } else {
                    LogCollector.w("VPN", "Skip Tun2Socks: no SOCKS credentials")
                }
            }

            scope.launch { watchCore() }
        } catch (e: Exception) {
            LogCollector.e("VPN", "startTunnel: ${e.message}")
            updateNotif("Error: ${e.message?.take(40)}")
            if (!killSwitch) stopSelf()
        }
    }

    /**
     * Split tunneling: ALL — весь трафик через VPN, INCLUDE — только
     * выбранные приложения, EXCLUDE — все, кроме выбранных.
     *
     * В INCLUDE-режиме нельзя смешивать allow/disallow (Builder бросает
     * исключение), поэтому себя просто не вносим в allow-список — трафик
     * приложения останется прямым. В остальных режимах исключаем себя
     * явно, чтобы не зациклить TUN → sing-box → TUN.
     */
    private suspend fun applySplitTunneling(builder: Builder) {
        val settings = SettingsStore(this)
        val mode = settings.splitMode()
        val packages = settings.splitPackages()

        if (mode == SplitTunnelMode.ALL || packages.isEmpty()) {
            // Пустой список в INCLUDE/EXCLUDE равен режиму «весь трафик»
            excludeSelf(builder)
            LogCollector.i("VPN", "Split: ALL traffic")
            return
        }

        when (mode) {
            SplitTunnelMode.INCLUDE -> {
                var added = 0
                for (pkg in packages) {
                    if (pkg == packageName) continue  // себя не заворачиваем
                    try {
                        builder.addAllowedApplication(pkg)
                        added++
                    } catch (e: Exception) {
                        LogCollector.w("VPN", "split allow $pkg: ${e.message}")
                    }
                }
                LogCollector.i("VPN", "Split INCLUDE: $added apps via VPN")
            }
            SplitTunnelMode.EXCLUDE -> {
                excludeSelf(builder)
                var added = 0
                for (pkg in packages) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        added++
                    } catch (e: Exception) {
                        LogCollector.w("VPN", "split disallow $pkg: ${e.message}")
                    }
                }
                LogCollector.i("VPN", "Split EXCLUDE: $added apps bypass VPN")
            }
            SplitTunnelMode.ALL -> excludeSelf(builder)
        }
    }

    private fun excludeSelf(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }
    }

    /**
     * Регистрирует NetworkCallback: при появлении новой сети (переключение
     * Wi-Fi ↔ LTE) даём интерфейсу подняться и проверяем доступность сервера.
     * Если сервер недоступен — шлём ACTION_CORE_DIED для ротации конфига.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    LogCollector.i("VPN", "Network available — check server")
                    scope.launch {
                        delay(4000) // даём новому интерфейсу подняться
                        val host = currentHost ?: return@launch
                        val port = currentPort
                        if (port <= 0 || userStop) return@launch
                        val ok = HealthMonitor.isServerReachable(host, port)
                        if (!ok) {
                            LogCollector.w("VPN", "Server unreachable after network change")
                            updateNotif("Rotating after network change…")
                            try {
                                sendBroadcast(Intent(ACTION_CORE_DIED).setPackage(packageName))
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
            LogCollector.i("VPN", "Network watcher registered")
        } catch (e: Exception) {
            LogCollector.w("VPN", "netwatch: ${e.message}")
        }
    }

    private fun stopNetworkWatch() {
        try {
            networkCallback?.let {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private suspend fun watchCore() {
        val p = coreProcess ?: return
        while (coroutineContext.isActive && !userStop) {
            delay(1500)
            try {
                p.exitValue()
                LogCollector.e("VPN", "Core process exited")
                try {
                    sendBroadcast(Intent(ACTION_CORE_DIED).setPackage(packageName))
                } catch (_: Exception) {}
                if (killSwitch && !userStop) {
                    updateNotif("Kill-switch active")
                } else {
                    stopTunnel(true)
                    stopSelf()
                }
                break
            } catch (_: IllegalThreadStateException) {
                // still running
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun stopTunnel(graceful: Boolean) {
        try {
            stopNetworkWatch()
            Tun2SocksBridge.stop()
            coreProcess?.destroy()
            coreProcess = null
            if (graceful || !killSwitch || userStop) {
                vpnInterface?.close()
                vpnInterface = null
            }
            LogCollector.i("VPN", "Tunnel stopped")
        } catch (e: Exception) {
            LogCollector.e("VPN", "stopTunnel: ${e.message}")
        }
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "ClearPass VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ClearPass")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(text))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopNetworkWatch()
        stopTunnel(userStop)
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        userStop = true
        stopTunnel(true)
        stopSelf()
    }
}
