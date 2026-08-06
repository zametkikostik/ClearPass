package com.clearpass.app.core

import android.content.Context
import com.clearpass.app.util.LogCollector
import java.util.concurrent.TimeUnit

object CoreProbe {

    fun tryVersion(context: Context): String? {
        return try {
            val bin = CoreBootstrap.resolveBinary(context) ?: return null
            bin.setExecutable(true)
            val pb = ProcessBuilder(bin.absolutePath, "version")
            pb.redirectErrorStream(true)
            pb.directory(context.filesDir)
            val proc = pb.start()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                LogCollector.w("Probe", "version timeout")
                return null
            }
            val out = proc.inputStream.bufferedReader().readText().trim().take(300)
            LogCollector.i("Probe", "version: $out")
            out.ifBlank { null }
        } catch (e: Exception) {
            LogCollector.w("Probe", "version fail: ${e.message}")
            null
        }
    }
}
