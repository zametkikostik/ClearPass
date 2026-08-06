package com.clearpass.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.manualConfigDataStore by preferencesDataStore("manual_configs")

class ManualConfigStore(private val context: Context) {

    private val KEY = stringSetPreferencesKey("uris")

    val urisFlow: Flow<List<String>> = context.manualConfigDataStore.data.map { prefs ->
        (prefs[KEY] ?: emptySet()).toList().sorted()
    }

    suspend fun getAll(): List<String> = urisFlow.first()

    suspend fun add(uri: String): Boolean {
        val cleaned = uri.trim()
        if (cleaned.isBlank()) return false
        if (!cleaned.contains("://")) return false
        context.manualConfigDataStore.edit { prefs ->
            val cur = prefs[KEY] ?: emptySet()
            prefs[KEY] = cur + cleaned
        }
        return true
    }

    suspend fun remove(uri: String) {
        context.manualConfigDataStore.edit { prefs ->
            val cur = prefs[KEY] ?: emptySet()
            prefs[KEY] = cur - uri
        }
    }

    suspend fun clear() {
        context.manualConfigDataStore.edit { it.remove(KEY) }
    }
}
