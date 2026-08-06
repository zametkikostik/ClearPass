package com.clearpass.app.core

import android.content.Context
import com.clearpass.app.util.LogCollector
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object CoreBootstrap {

    private const val GEOIP = "geoip.db"
    private const val GEOSITE = "geosite.db"
    private const val CORE_NAME = "libsingbox.so"

    fun prepare(context: Context): File {
        val dir = context.filesDir
        try {
            copyAssetIfExists(context, GEOIP, File(dir, GEOIP))
            copyAssetIfExists(context, GEOSITE, File(dir, GEOSITE))
            ensureCoreInFilesDir(context)
        } catch (e: Exception) {
            LogCollector.w("Core", "prepare: ${e.message}")
        }
        return dir
    }

    fun hasBinary(context: Context): Boolean = resolveBinary(context) != null

    fun resolveBinary(context: Context): File? {
        val candidates = listOf(
            File(context.applicationInfo.nativeLibraryDir, CORE_NAME),
            File(context.applicationInfo.nativeLibraryDir, "sing-box"),
            File(context.filesDir, CORE_NAME),
            File(context.filesDir, "sing-box")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 1000L }
    }

    fun logCoreStatus(context: Context) {
        try {
            val bin = resolveBinary(context)
            if (bin == null) {
                LogCollector.w("Core", "NO BINARY — SAFE MODE")
                return
            }
            LogCollector.i(
                "Core",
                "binary=${bin.absolutePath} size=${bin.length()} canExec=${bin.canExecute()}"
            )
        } catch (e: Exception) {
            LogCollector.w("Core", e.message ?: "status fail")
        }
    }

    private fun ensureCoreInFilesDir(context: Context) {
        try {
            val native = File(context.applicationInfo.nativeLibraryDir, CORE_NAME)
            val dest = File(context.filesDir, CORE_NAME)
            if (!native.exists() || native.length() < 1000L) return
            if (dest.exists() && dest.length() == native.length()) {
                dest.setExecutable(true)
                return
            }
            FileInputStream(native).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.setReadable(true)
            dest.setExecutable(true)
            LogCollector.i("Core", "Copied core to filesDir (${dest.length()} bytes)")
        } catch (e: Exception) {
            LogCollector.d("Core", "filesDir copy skip: ${e.message}")
        }
    }

    private fun copyAssetIfExists(context: Context, name: String, target: File) {
        try {
            context.assets.open(name).use { input ->
                if (target.exists() && target.length() > 0) return
                target.outputStream().use { output -> input.copyTo(output) }
                LogCollector.i("Core", "Copied asset $name")
            }
        } catch (_: Exception) {
        }
    }
}
