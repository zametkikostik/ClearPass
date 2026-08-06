package com.clearpass.app.tunnel

import com.clearpass.app.util.LogCollector

/**
 * JNI surface matching SocksTun / hev library API.
 * Requires libhev-jni.so built with PKGNAME=com/clearpass/app/tunnel CLSNAME=HevJni.
 */
object HevJni {

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = tryLoad("hev-jni") || tryLoad("hev-socks5-tunnel")
        if (available) {
            LogCollector.i("HevJni", "native library loaded")
        } else {
            LogCollector.w("HevJni", "JNI lib not loaded — will use CLI fallback")
        }
    }

    private fun tryLoad(name: String): Boolean {
        return try {
            System.loadLibrary(name)
            true
        } catch (e: UnsatisfiedLinkError) {
            LogCollector.d("HevJni", "load $name: ${e.message}")
            false
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean
}
