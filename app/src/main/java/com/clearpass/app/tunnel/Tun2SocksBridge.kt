package com.clearpass.app.tunnel

import android.content.Context
import com.clearpass.app.util.LogCollector
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object Tun2SocksBridge {

    @Volatile
    var isRunning: Boolean = false
        private set

    private var process: Process? = null
    private var mode: String = "none"

    fun start(
        context: Context,
        tunFd: Int,
        socksHost: String = "127.0.0.1",
        socksPort: Int = 10808,
        socksUser: String,
        socksPass: String
    ): Boolean {
        stop()
        return try {
            if (socksUser.isBlank() || socksPass.length < 8) {
                LogCollector.e("Tun2Socks", "SOCKS auth required")
                return false
            }

            val conf = File(context.filesDir, "hev-socks5.yml")
            conf.writeText(buildConfig(socksHost, socksPort, socksUser, socksPass))
            LogCollector.i("Tun2Socks", "config=${conf.absolutePath} tunFd=$tunFd")

            if (HevJni.available && tunFd >= 0) {
                return try {
                    val ok = HevJni.TProxyStartService(conf.absolutePath, tunFd)
                    if (ok) {
                        isRunning = true
                        mode = "jni"
                        LogCollector.i("Tun2Socks", "JNI tunnel started fd=$tunFd")
                    } else {
                        LogCollector.e("Tun2Socks", "JNI TProxyStartService returned false")
                    }
                    ok
                } catch (e: Throwable) {
                    LogCollector.e("Tun2Socks", "JNI call failed: ${e.message}")
                    startCli(context, conf, tunFd)
                }
            }

            startCli(context, conf, tunFd)
        } catch (e: Exception) {
            LogCollector.e("Tun2Socks", "start failed: ${e.message}")
            isRunning = false
            false
        }
    }

    private fun startCli(context: Context, conf: File, tunFd: Int): Boolean {
        val bin = resolveBinary(context)
        if (bin == null) {
            LogCollector.e("Tun2Socks", "hev CLI binary not found")
            return false
        }
        bin.setExecutable(true)

        val pb = ProcessBuilder(bin.absolutePath, conf.absolutePath)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        process = pb.start()
        isRunning = true
        mode = "cli"
        LogCollector.i("Tun2Socks", "CLI started (tunFd=$tunFd note: CLI may ignore FD on Android)")

        Thread({
            try {
                Thread.sleep(1000)
                try {
                    val code = process?.exitValue()
                    LogCollector.w(
                        "Tun2Socks",
                        "CLI exited code=$code — prefer libhev-jni.so for stable VPN"
                    )
                    isRunning = false
                } catch (_: IllegalThreadStateException) {
                    LogCollector.i("Tun2Socks", "CLI still running after 1s")
                }
            } catch (_: Exception) {
            }
        }, "hev-watch").apply { isDaemon = true; start() }

        return true
    }

    fun stop() {
        try {
            if (mode == "jni" && HevJni.available) {
                try {
                    HevJni.TProxyStopService()
                } catch (_: Throwable) {
                }
            }
            process?.destroy()
            process = null
        } catch (_: Exception) {
        }
        isRunning = false
        mode = "none"
        LogCollector.i("Tun2Socks", "stopped")
    }

    fun currentMode(): String = mode

    private fun buildConfig(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): String {
        return """
            tunnel:
              mtu: 1500
              ipv4: 10.10.0.2
              icmp: 'off'
            socks5:
              port: $port
              address: $host
              udp: 'udp'
              username: '$user'
              password: '$pass'
            misc:
              log-level: warn
              limit-nofile: 65535
        """.trimIndent()
    }

    private fun resolveBinary(context: Context): File? {
        val names = listOf("libhev-socks5-tunnel.so", "hev-socks5-tunnel")
        val dirs = listOf(
            context.applicationInfo.nativeLibraryDir,
            context.filesDir.absolutePath
        )
        for (dir in dirs) {
            for (name in names) {
                val f = File(dir, name)
                if (f.exists() && f.length() > 1000) {
                    val dest = File(context.filesDir, "hev-socks5-tunnel")
                    try {
                        if (!dest.exists() || dest.length() != f.length()) {
                            FileInputStream(f).use { input ->
                                FileOutputStream(dest).use { output -> input.copyTo(output) }
                            }
                        }
                        dest.setExecutable(true)
                        return dest
                    } catch (_: Exception) {
                        return f
                    }
                }
            }
        }
        return null
    }
}
