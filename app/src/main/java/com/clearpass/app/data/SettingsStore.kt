package com.clearpass.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("app_settings")

enum class SourceMode {
    WHITE_ONLY,
    BLACK_ONLY,
    BOTH,
    AUTO;  // Авто-режим: днём белые списки, ночью/при блокировках чёрные

    companion object {
        fun from(raw: String?): SourceMode = when (raw) {
            "WHITE_ONLY" -> WHITE_ONLY
            "BLACK_ONLY" -> BLACK_ONLY
            "AUTO" -> AUTO
            else -> BOTH
        }
    }
}

/**
 * Режим split tunneling: весь трафик через VPN, только выбранные
 * приложения или все, кроме выбранных.
 */
enum class SplitTunnelMode {
    ALL,       // весь трафик через VPN (по умолчанию)
    INCLUDE,   // только выбранные приложения
    EXCLUDE;   // все, кроме выбранных

    companion object {
        fun from(raw: String?): SplitTunnelMode = when (raw) {
            "INCLUDE" -> INCLUDE
            "EXCLUDE" -> EXCLUDE
            else -> ALL
        }
    }
}

class SettingsStore(private val context: Context) {

    private val KILL_SWITCH = booleanPreferencesKey("kill_switch")
    private val UPDATE_HOURS = intPreferencesKey("update_hours")
    private val SOURCE_MODE = stringPreferencesKey("source_mode")
    private val AUTO_MODE_ENABLED = booleanPreferencesKey("auto_mode_enabled")
    private val AUTO_CONNECT_ON_BOOT = booleanPreferencesKey("auto_connect_on_boot")
    private val SPLIT_MODE = stringPreferencesKey("split_mode")
    private val SPLIT_PACKAGES = stringSetPreferencesKey("split_packages")

    val killSwitchFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KILL_SWITCH] ?: true
    }

    val updateHoursFlow: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[UPDATE_HOURS] ?: 3
    }

    val sourceModeFlow: Flow<SourceMode> = context.settingsDataStore.data.map { prefs ->
        SourceMode.from(prefs[SOURCE_MODE])
    }

    val autoModeEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[AUTO_MODE_ENABLED] ?: false
    }

    // Автоподключение после загрузки устройства (BootReceiver)
    val autoConnectOnBootFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[AUTO_CONNECT_ON_BOOT] ?: false
    }

    // Split tunneling
    val splitModeFlow: Flow<SplitTunnelMode> = context.settingsDataStore.data.map { prefs ->
        SplitTunnelMode.from(prefs[SPLIT_MODE])
    }

    val splitPackagesFlow: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[SPLIT_PACKAGES] ?: emptySet()
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

    suspend fun isAutoModeEnabled(): Boolean = autoModeEnabledFlow.first()

    suspend fun setAutoModeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_MODE_ENABLED] = enabled }
    }

    // Автоподключение после загрузки устройства

    suspend fun isAutoConnectOnBoot(): Boolean = autoConnectOnBootFlow.first()

    suspend fun setAutoConnectOnBoot(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_CONNECT_ON_BOOT] = enabled }
    }

    // Split tunneling

    suspend fun splitMode(): SplitTunnelMode = splitModeFlow.first()

    suspend fun setSplitMode(mode: SplitTunnelMode) {
        context.settingsDataStore.edit { it[SPLIT_MODE] = mode.name }
    }

    suspend fun splitPackages(): Set<String> = splitPackagesFlow.first()

    suspend fun setSplitPackages(packages: Set<String>) {
        context.settingsDataStore.edit { it[SPLIT_PACKAGES] = packages }
    }

    /**
     * Возвращает актуальный режим с учётом AUTO-режима.
     * В AUTO-режиме: днём (6:00-20:00) используем WHITE_ONLY, ночью BLACK_ONLY.
     */
    suspend fun getEffectiveSourceMode(): SourceMode {
        val mode = getSourceMode()
        if (mode != SourceMode.AUTO) return mode

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 6..19) {
            SourceMode.WHITE_ONLY
        } else {
            SourceMode.BLACK_ONLY
        }
    }
}
