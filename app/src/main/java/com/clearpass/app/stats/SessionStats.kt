package com.clearpass.app.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionInfo(
    val server: String = "",
    val sni: String = "",
    val protocol: String = "",
    val startedAt: Long = 0L,
    val rotations: Int = 0
)

object SessionStats {

    private val _session = MutableStateFlow(SessionInfo())
    val session: StateFlow<SessionInfo> = _session.asStateFlow()

    fun onConnected(server: String, sni: String, protocol: String) {
        _session.value = SessionInfo(
            server = server,
            sni = sni,
            protocol = protocol,
            startedAt = System.currentTimeMillis(),
            rotations = _session.value.rotations
        )
    }

    fun onRotated(server: String, sni: String, protocol: String) {
        val prev = _session.value
        _session.value = SessionInfo(
            server = server,
            sni = sni,
            protocol = protocol,
            startedAt = System.currentTimeMillis(),
            rotations = prev.rotations + 1
        )
    }

    fun onDisconnected() {
        _session.value = SessionInfo(rotations = _session.value.rotations)
    }

    fun durationSec(): Long {
        val s = _session.value.startedAt
        if (s == 0L) return 0L
        return (System.currentTimeMillis() - s) / 1000L
    }
}
