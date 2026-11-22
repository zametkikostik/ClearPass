package com.clearpass.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Самообновление APK из GitHub Releases (zametkikostik/ClearPass).
 *
 * Схема: /releases/latest → сравниваем versionName → качаем ассет
 * ClearPass-release.apk в cacheDir → отдаём в системный установщик через
 * FileProvider. Без root и без записи в общие папки — только cacheDir
 * своего приложения.
 */
object SelfUpdater {

    private const val API_LATEST =
        "https://api.github.com/repos/zametkikostik/ClearPass/releases/latest"
    private const val ASSET_NAME = "ClearPass-release.apk"
    private const val MIN_APK_BYTES = 1_000_000L // защита от битой загрузки

    data class UpdateInfo(
        val tag: String,        // v0.3.0
        val version: String,    // 0.3.0
        val apkUrl: String,
        val sizeBytes: Long,
        val releaseUrl: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Текущая установленная версия (versionName). */
    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Проверяет /releases/latest: возвращает UpdateInfo, если там новее нашей. */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(API_LATEST)
                .header("User-Agent", "ClearPass/${currentVersion(context)}")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogCollector.w("Updater", "check: HTTP ${resp.code}")
                    return@withContext null
                }
                val raw = resp.body?.string() ?: return@withContext null
                val json = JSONObject(raw)
                val tag = json.optString("tag_name", "")
                if (tag.isBlank()) return@withContext null
                val remote = tag.removePrefix("v").trim()
                if (!isNewer(remote, currentVersion(context))) {
                    LogCollector.i("Updater", "Up to date ($remote)")
                    return@withContext null
                }
                var apkUrl: String? = null
                var size = 0L
                val assets = json.optJSONArray("assets") ?: return@withContext null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == ASSET_NAME) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                if (apkUrl.isNullOrBlank()) {
                    LogCollector.w("Updater", "Release $tag has no $ASSET_NAME")
                    return@withContext null
                }
                LogCollector.i("Updater", "Update available: $tag")
                UpdateInfo(tag, remote, apkUrl, size, json.optString("html_url"))
            }
        } catch (e: Exception) {
            LogCollector.w("Updater", "check: ${e.message}")
            null
        }
    }

    /** Скачивает APK в cacheDir и запускает системную установку. */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val dest = File(context.cacheDir, "ClearPass-update.apk")
        try {
            val req = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "ClearPass/${currentVersion(context)}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogCollector.e("Updater", "download: HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            if (dest.length() < MIN_APK_BYTES) {
                LogCollector.e("Updater", "downloaded file too small: ${dest.length()}")
                return@withContext false
            }
            LogCollector.i("Updater", "APK downloaded: ${dest.length()} bytes")
            installApk(context, dest)
            true
        } catch (e: Exception) {
            LogCollector.e("Updater", "download: ${e.message}")
            false
        }
    }

    private fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Сравнение версий вида 0.3.0 > 0.2.1. Суффиксы (-debug) игнорируем. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return true
        if (r[0] != c[0]) return r[0] > c[0]
        if (r[1] != c[1]) return r[1] > c[1]
        return r[2] > c[2]
    }

    private fun parseVersion(v: String): IntArray? {
        val clean = v.trim().removePrefix("v").substringBefore('-')
        val parts = clean.split('.')
        if (parts.size < 3) return null
        val nums = IntArray(3)
        for (i in 0..2) {
            nums[i] = parts[i].toIntOrNull() ?: return null
        }
        return nums
    }
}
