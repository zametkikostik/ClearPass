package com.clearpass.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clearpass.app.tester.TestedLink
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.configCacheStore by preferencesDataStore("config_cache")

@Serializable
data class CachedConfig(
    val uri: String,
    val address: String,
    val port: Int,
    val sni: String,
    val protocol: String,
    val latencyMs: Int,
    val score: Int,
    val lastTested: Long = System.currentTimeMillis(),
    val successCount: Int = 1,
    val failCount: Int = 0
)

class ConfigCacheStore(private val context: Context) {

    private val KEY = stringPreferencesKey("items")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun getAll(): List<CachedConfig> {
        return try {
            val raw = context.configCacheStore.data.first()[KEY] ?: return emptyList()
            json.decodeFromString<List<CachedConfig>>(raw)
        } catch (e: Exception) {
            LogCollector.e("Cache", "read: ${e.message}")
            emptyList()
        }
    }

    suspend fun getTop(limit: Int = 15): List<CachedConfig> {
        return getAll()
            .sortedWith(compareByDescending<CachedConfig> { it.score }.thenBy { it.latencyMs })
            .take(limit)
    }

    suspend fun saveTested(tested: List<TestedLink>) {
        if (tested.isEmpty()) return
        try {
            val existing = getAll().associateBy { it.uri }.toMutableMap()
            for (t in tested) {
                val prev = existing[t.uri]
                existing[t.uri] = CachedConfig(
                    uri = t.uri,
                    address = t.address,
                    port = t.port,
                    sni = t.sni,
                    protocol = t.protocol,
                    latencyMs = t.latencyMs,
                    score = t.score,
                    lastTested = System.currentTimeMillis(),
                    successCount = (prev?.successCount ?: 0) + 1,
                    failCount = prev?.failCount ?: 0
                )
            }
            persist(existing.values.toList())
            LogCollector.i("Cache", "Saved ${tested.size}, total ${existing.size}")
        } catch (e: Exception) {
            LogCollector.e("Cache", "save: ${e.message}")
        }
    }

    suspend fun markFailed(uri: String) {
        try {
            val all = getAll().toMutableList()
            val idx = all.indexOfFirst { it.uri == uri }
            if (idx >= 0) {
                val c = all[idx]
                all[idx] = c.copy(
                    failCount = c.failCount + 1,
                    score = (c.score - 15).coerceAtLeast(0)
                )
                persist(all)
            }
        } catch (_: Exception) {
        }
    }

    suspend fun markSuccess(uri: String, latencyMs: Int) {
        try {
            val all = getAll().toMutableList()
            val idx = all.indexOfFirst { it.uri == uri }
            if (idx >= 0) {
                val c = all[idx]
                all[idx] = c.copy(
                    successCount = c.successCount + 1,
                    latencyMs = latencyMs,
                    lastTested = System.currentTimeMillis(),
                    score = (c.score + 5).coerceAtMost(100)
                )
                persist(all)
            }
        } catch (_: Exception) {
        }
    }

    suspend fun clear() {
        context.configCacheStore.edit { it.remove(KEY) }
    }

    private suspend fun persist(items: List<CachedConfig>) {
        val trimmed = items
            .sortedWith(compareByDescending<CachedConfig> { it.score }.thenBy { it.latencyMs })
            .take(50)
        context.configCacheStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(trimmed)
        }
    }
}
