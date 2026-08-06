package com.clearpass.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.subscriptionDataStore by preferencesDataStore("subscriptions")

class SubscriptionStore(private val context: Context) {

    private val KEY = stringSetPreferencesKey("urls")

    val urlsFlow: Flow<List<String>> = context.subscriptionDataStore.data.map { prefs ->
        (prefs[KEY] ?: emptySet()).toList().sorted()
    }

    suspend fun getAll(): List<String> = urlsFlow.first()

    suspend fun add(url: String): Boolean {
        val cleaned = url.trim()
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return false
        context.subscriptionDataStore.edit { prefs ->
            val cur = prefs[KEY] ?: emptySet()
            prefs[KEY] = cur + cleaned
        }
        return true
    }

    suspend fun remove(url: String) {
        context.subscriptionDataStore.edit { prefs ->
            val cur = prefs[KEY] ?: emptySet()
            prefs[KEY] = cur - url
        }
    }
}
