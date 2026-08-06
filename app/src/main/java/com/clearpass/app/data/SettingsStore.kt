package com.clearpass.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("app_settings")

enum class SourceMode {
    WHITE_ONLY,
    BLACK_ONLY,
    BOTH;

    companion object {
        fun from(raw: String?): SourceMode = when (raw) {
            "WHITE_ONLY" -> WHITE_ONLY
            "BLACK_ONLY" -> BLACK_ONLY
            else -> BOTH
        }
    }
}

class SettingsStore(private val context: Context) {

    private val KILL_SWITCH = booleanPreferencesKey("kill_switch")
    private val UPDATE_HOURS = intPreferencesKey("update_hours")
    private val SOURCE_MODE = stringPreferencesKey("source_mode")

    val killSwitchFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KILL_SWITCH] ?: true
    }

    val updateHoursFlow: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[UPDATE_HOURS] ?: 3
    }

    val sourceModeFlow: Flow<SourceMode> = context.settingsDataStore.data.map { prefs ->
        SourceMode.from(prefs[SOURCE_MODE])
    }

    suspend fun isKillSwitch(): Boolean = killSwitchFlow.first()

    suspend fun setKillSwitch(enabled: Boolean) {
        context.settingsDataStore.edit { it[KILL_SWITCH] = enabled }
    }

    suspend fun setUpdateHours(hours: Int) {
        context.settingsDataStore.edit { it[UPDATE_HOURS] = hours.coerceIn(1, 48) }
    }

    suspend fun getSourceMode(): SourceMode = sourceModeFlow.first()

    suspend fun setSourceMode(mode: SourceMode) {
        context.settingsDataStore.edit { it[SOURCE_MODE] = mode.name }
    }
}
