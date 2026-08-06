package com.clearpass.app.vpn

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(
        val address: String,
        val sni: String,
        val protocol: String,
        val latencyMs: Int = -1
    ) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
