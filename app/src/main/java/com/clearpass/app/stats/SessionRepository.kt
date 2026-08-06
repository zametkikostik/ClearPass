package com.clearpass.app.stats

import android.content.Context
import com.clearpass.app.data.AppDatabase
import com.clearpass.app.data.SessionEntity
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.flow.Flow

class SessionRepository(context: Context) {

    private val dao = AppDatabase.get(context).sessionDao()
    private var currentId: Long? = null
    private var startedAt: Long = 0

    fun observeRecent(limit: Int = 40): Flow<List<SessionEntity>> = dao.recent(limit)

    suspend fun start(server: String, sni: String, protocol: String, latencyMs: Int) {
        try {
            startedAt = System.currentTimeMillis()
            currentId = dao.insert(
                SessionEntity(
                    server = server,
                    sni = sni,
                    protocol = protocol,
                    startedAt = startedAt,
                    latencyMs = latencyMs
                )
            )
        } catch (e: Exception) {
            LogCollector.w("SessionRepo", e.message ?: "start fail")
        }
    }

    suspend fun finish(reason: String) {
        try {
            val id = currentId ?: return
            val now = System.currentTimeMillis()
            dao.finish(id, now, (now - startedAt) / 1000, reason)
            currentId = null
        } catch (e: Exception) {
            LogCollector.w("SessionRepo", e.message ?: "finish fail")
        }
    }

    suspend fun cleanOld(days: Int = 30) {
        try {
            val threshold = System.currentTimeMillis() - days * 24L * 3600_000L
            dao.deleteOlderThan(threshold)
        } catch (_: Exception) {
        }
    }
}
