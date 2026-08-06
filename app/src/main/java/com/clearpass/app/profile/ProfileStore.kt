package com.clearpass.app.profile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.profileDataStore by preferencesDataStore("profiles")

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val notes: String = ""
)

class ProfileStore(private val context: Context) {

    private val KEY = stringPreferencesKey("profiles_json")
    private val ACTIVE = stringPreferencesKey("active_id")
    private val json = Json { ignoreUnknownKeys = true }

    val profilesFlow: Flow<List<Profile>> = context.profileDataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptyList()
        try {
            json.decodeFromString<List<Profile>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun ensureDefault() {
        val all = profilesFlow.first()
        if (all.isEmpty()) {
            val p = Profile(id = "default", name = "Default")
            saveAll(listOf(p))
            setActive(p.id)
        }
    }

    suspend fun getAll(): List<Profile> = profilesFlow.first()

    suspend fun saveAll(list: List<Profile>) {
        context.profileDataStore.edit {
            it[KEY] = json.encodeToString(list)
        }
    }

    suspend fun setActive(id: String) {
        context.profileDataStore.edit { it[ACTIVE] = id }
    }

    suspend fun activeId(): String? =
        context.profileDataStore.data.map { it[ACTIVE] }.first()
}
