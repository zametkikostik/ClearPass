package com.clearpass.app.core

import android.content.Context
import com.clearpass.app.util.LogCollector
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Prepares working directory and ensures a runnable sing-box binary exists.
 * Order: packaged jniLibs → filesDir copy → download official android-arm64 tarball.
 */
object CoreBootstrap {

    private const val GEOIP = "geoip.db"
    private const val GEOSITE = "geosite.db"
    private const val CORE_NAME = "libsingbox.so"
    private const val CORE_ALT = "sing-box"

    /** Official SagerNet android-arm64 package (contains binary "sing-box"). */
    private const val DEFAULT_CORE_TGZ =
        "https://github.com/SagerNet/sing-box/releases/download/v1.13.16/sing-box-1.13.16-android-arm64.tar.gz"

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
            File(context.applicationInfo.nativeLibraryDir, CORE_ALT),
            File(context.filesDir, CORE_NAME),
            File(context.filesDir, CORE_ALT)
        )
        return candidates.firstOrNull { it.exists() && it.length() > 1_000_000L }
    }

    fun logCoreStatus(context: Context) {
        try {
            val bin = resolveBinary(context)
            if (bin == null) {
                LogCollector.w("Core", "NO BINARY — SAFE MODE (will try download on connect)")
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

    /**
     * Blocks until a core binary is available or download fails.
     * Call from IO dispatcher before starting VPN.
     */
    fun ensureCore(context: Context): Boolean {
        prepare(context)
        if (resolveBinary(context) != null) return true

        LogCollector.i("Core", "Downloading sing-box android-arm64…")
        return try {
            downloadAndExtractOfficial(context)
            val ok = resolveBinary(context) != null
            if (ok) LogCollector.i("Core", "Core ready: ${resolveBinary(context)?.absolutePath}")
            else LogCollector.e("Core", "Download finished but binary still missing")
            ok
        } catch (e: Exception) {
            LogCollector.e("Core", "download failed: ${e.message}")
            false
        }
    }

    private fun downloadAndExtractOfficial(context: Context): Boolean {
        val dir = context.filesDir
        val tgz = File(dir, "sing-box-dl.tar.gz")
        try {
            downloadTo(DEFAULT_CORE_TGZ, tgz)
            if (tgz.length() < 1_000_000L) {
                LogCollector.e("Core", "Downloaded file too small: ${tgz.length()}")
                return false
            }
            // Prefer pure GZIP+TAR without external libs — manual extract of "sing-box" entry
            return extractSingBoxFromTarGz(tgz, File(dir, CORE_ALT)).also {
                try { tgz.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            LogCollector.e("Core", "extract: ${e.message}")
            // Fallback: try raw binary URL patterns (user release)
            return tryDownloadPlain(
                context,
                listOf(
                    "https://github.com/zametkikostik/ClearPass/releases/download/v0.2.1/libsingbox.so",
                    "https://github.com/zametkikostik/ClearPass/releases/latest/download/libsingbox.so"
                )
            )
        }
    }

    private fun tryDownloadPlain(context: Context, urls: List<String>): Boolean {
        val dest = File(context.filesDir, CORE_NAME)
        for (u in urls) {
            try {
                LogCollector.i("Core", "GET $u")
                downloadTo(u, dest)
                if (dest.length() > 1_000_000L) {
                    dest.setReadable(true)
                    dest.setExecutable(true)
                    LogCollector.i("Core", "Saved plain core ${dest.length()} bytes")
                    return true
                }
            } catch (e: Exception) {
                LogCollector.w("Core", "plain fail: ${e.message}")
            }
        }
        return false
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClearPass/0.2")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Minimal tar.gz reader (ustar) — finds entry named "sing-box" and writes it.
     */
    private fun extractSingBoxFromTarGz(tgz: File, out: File): Boolean {
        GZIPInputStream(BufferedInputStream(FileInputStream(tgz))).use { gis ->
            val buf = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = gis.read(buf, read, 512 - read)
                    if (n < 0) return false
                    read += n
                }
                // empty block = end
                if (buf.all { it.toInt() == 0 }) return false

                val name = buf.copyOfRange(0, 100).toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val sizeOctal = buf.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val size = sizeOctal.toLongOrNull(8) ?: 0L
                val typeFlag = buf[156].toInt().toChar()

                val base = name.substringAfterLast('/')
                if ((typeFlag == '0' || typeFlag == '\u0000') && (base == "sing-box" || base == CORE_NAME)) {
                    FileOutputStream(out).use { fos ->
                        var left = size
                        val chunk = ByteArray(8192)
                        while (left > 0) {
                            val n = gis.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                            if (n < 0) break
                            fos.write(chunk, 0, n)
                            left -= n
                        }
                    }
                    // skip padding to 512
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    if (pad > 0) gis.skip(pad.toLong())
                    out.setReadable(true)
                    out.setExecutable(true)
                    LogCollector.i("Core", "Extracted $base → ${out.absolutePath} (${out.length()} bytes)")
                    return out.length() > 1_000_000L
                } else {
                    // skip file data + padding
                    var left = size
                    while (left > 0) {
                        val sk = gis.skip(left)
                        if (sk <= 0) {
                            // force read if skip fails
                            if (gis.read() < 0) break
                            left--
                        } else left -= sk
                    }
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    if (pad > 0) gis.skip(pad.toLong())
                }
            }
        }
        return false
    }

    private fun ensureCoreInFilesDir(context: Context) {
        try {
            val native = File(context.applicationInfo.nativeLibraryDir, CORE_NAME)
            val dest = File(context.filesDir, CORE_NAME)
            if (!native.exists() || native.length() < 1_000_000L) return
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
