package com.clearpass.app.parser

import android.net.Uri

sealed class ProxyLink {
    abstract val raw: String
    abstract val address: String
    abstract val port: Int
    abstract val name: String?

    data class Vless(
        override val raw: String,
        override val address: String,
        override val port: Int,
        val uuid: String,
        val sni: String?,
        val pbk: String?,
        val sid: String?,
        val flow: String?,
        val fp: String?,
        val spx: String?,
        override val name: String?
    ) : ProxyLink()

    data class Hysteria2(
        override val raw: String,
        override val address: String,
        override val port: Int,
        val auth: String?,
        val sni: String?,
        val insecure: Boolean = false,
        override val name: String?
    ) : ProxyLink()

    data class Tuic(
        override val raw: String,
        override val address: String,
        override val port: Int,
        val uuid: String,
        val password: String?,
        val sni: String?,
        val congestion: String? = "bbr",
        override val name: String?
    ) : ProxyLink()
}

object ProxyLinkParser {

    fun parse(link: String): ProxyLink? {
        return try {
            val cleaned = link.trim()
            when {
                cleaned.startsWith("vless://", true) -> parseVless(cleaned)
                cleaned.startsWith("hysteria2://", true) || cleaned.startsWith("hy2://", true) ->
                    parseHysteria2(cleaned)
                cleaned.startsWith("tuic://", true) -> parseTuic(cleaned)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVless(raw: String): ProxyLink.Vless? {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        val userInfo = uri.userInfo ?: return null
        return ProxyLink.Vless(
            raw = raw,
            address = host,
            port = if (uri.port != -1) uri.port else 443,
            uuid = userInfo,
            sni = uri.getQueryParameter("sni"),
            pbk = uri.getQueryParameter("pbk"),
            sid = uri.getQueryParameter("sid"),
            flow = uri.getQueryParameter("flow"),
            fp = uri.getQueryParameter("fp"),
            spx = uri.getQueryParameter("spx"),
            name = uri.fragment
        )
    }

    private fun parseHysteria2(raw: String): ProxyLink.Hysteria2? {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        return ProxyLink.Hysteria2(
            raw = raw,
            address = host,
            port = if (uri.port != -1) uri.port else 443,
            auth = uri.userInfo,
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("servername"),
            insecure = uri.getQueryParameter("insecure") == "1",
            name = uri.fragment
        )
    }

    private fun parseTuic(raw: String): ProxyLink.Tuic? {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        val parts = uri.userInfo?.split(":") ?: return null
        return ProxyLink.Tuic(
            raw = raw,
            address = host,
            port = if (uri.port != -1) uri.port else 443,
            uuid = parts.getOrNull(0) ?: return null,
            password = parts.getOrNull(1),
            sni = uri.getQueryParameter("sni"),
            congestion = uri.getQueryParameter("congestion_control") ?: "bbr",
            name = uri.fragment
        )
    }
}
