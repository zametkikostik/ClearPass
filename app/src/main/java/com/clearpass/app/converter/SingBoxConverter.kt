package com.clearpass.app.converter

import com.clearpass.app.parser.ProxyLink
import com.clearpass.app.whitelist.WhiteListManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

object SingBoxConverter {

    data class LocalAuth(val username: String, val password: String)

    fun convert(
        link: ProxyLink,
        localAuth: LocalAuth,
        enableTunInbound: Boolean = false
    ): String {
        require(localAuth.username.isNotBlank() && localAuth.password.length >= 12) {
            "Local inbound MUST have strong authentication"
        }

        val config = JSONObject()
        config.put("log", JSONObject().put("level", "warn").put("timestamp", true))
        config.put("dns", buildDns())
        config.put("inbounds", buildInbounds(localAuth, enableTunInbound))
        config.put("outbounds", buildOutbounds(link))
        config.put("route", buildRoute())
        config.put(
            "experimental",
            JSONObject().put(
                "clash_api",
                JSONObject()
                    .put("external_controller", "127.0.0.1:9090")
                    .put("secret", localAuth.password)
            )
        )
        return config.toString(2)
    }

    fun generateLocalAuth(): LocalAuth {
        val username = "cp_" + UUID.randomUUID().toString().take(8)
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val rnd = SecureRandom()
        val password = (1..20).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        return LocalAuth(username, password)
    }

    private fun buildDns(): JSONObject = JSONObject().apply {
        put(
            "servers",
            JSONArray()
                .put(
                    JSONObject()
                        .put("tag", "google")
                        .put("address", "https://dns.google/dns-query")
                        .put("detour", "proxy")
                )
                .put(
                    JSONObject()
                        .put("tag", "local")
                        .put("address", "local")
                        .put("detour", "direct")
                )
        )
        put("final", "google")
        put("strategy", "prefer_ipv4")
    }

    private fun buildInbounds(auth: LocalAuth, enableTun: Boolean): JSONArray {
        val arr = JSONArray()
        arr.put(
            JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", 10808)
                .put(
                    "users",
                    JSONArray().put(
                        JSONObject()
                            .put("username", auth.username)
                            .put("password", auth.password)
                    )
                )
        )
        if (enableTun) {
            arr.put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("inet4_address", "172.19.0.1/30")
                    .put("auto_route", true)
                    .put("strict_route", true)
                    .put("sniff", true)
            )
        }
        return arr
    }

    private fun buildOutbounds(link: ProxyLink): JSONArray {
        val proxy = when (link) {
            is ProxyLink.Vless -> buildVless(link)
            is ProxyLink.Hysteria2 -> buildHy2(link)
            is ProxyLink.Tuic -> buildTuic(link)
        }
        return JSONArray()
            .put(proxy)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "block").put("tag", "block"))
            .put(JSONObject().put("type", "dns").put("tag", "dns-out"))
    }

    private fun buildVless(link: ProxyLink.Vless): JSONObject = JSONObject()
        .put("type", "vless")
        .put("tag", "proxy")
        .put("server", link.address)
        .put("server_port", link.port)
        .put("uuid", link.uuid)
        .put("flow", link.flow ?: "")
        .put(
            "tls",
            JSONObject()
                .put("enabled", true)
                .put("server_name", link.sni ?: link.address)
                .put(
                    "utls",
                    JSONObject().put("enabled", true).put("fingerprint", link.fp ?: "chrome")
                )
                .put(
                    "reality",
                    JSONObject()
                        .put("enabled", true)
                        .put("public_key", link.pbk ?: "")
                        .put("short_id", link.sid ?: "")
                )
        )
        .put("packet_encoding", "xudp")

    private fun buildHy2(link: ProxyLink.Hysteria2): JSONObject = JSONObject()
        .put("type", "hysteria2")
        .put("tag", "proxy")
        .put("server", link.address)
        .put("server_port", link.port)
        .put("password", link.auth ?: "")
        .put(
            "tls",
            JSONObject()
                .put("enabled", true)
                .put("server_name", link.sni ?: link.address)
                .put("insecure", link.insecure)
        )

    private fun buildTuic(link: ProxyLink.Tuic): JSONObject = JSONObject()
        .put("type", "tuic")
        .put("tag", "proxy")
        .put("server", link.address)
        .put("server_port", link.port)
        .put("uuid", link.uuid)
        .put("password", link.password ?: "")
        .put("congestion_control", link.congestion ?: "bbr")
        .put(
            "tls",
            JSONObject()
                .put("enabled", true)
                .put("server_name", link.sni ?: link.address)
        )
        .put("udp_relay_mode", "native")

    private fun buildRoute(): JSONObject {
        val rules = JSONArray()
            .put(JSONObject().put("protocol", "dns").put("outbound", "dns-out"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
            .put(JSONObject().put("geoip", JSONArray().put("ru")).put("outbound", "direct"))
            .put(
                JSONObject()
                    .put(
                        "domain_suffix",
                        JSONArray().apply { WhiteListManager.whitelist.forEach { put(it) } }
                    )
                    .put("outbound", "direct")
            )
        return JSONObject()
            .put("rules", rules)
            .put("final", "proxy")
            .put("auto_detect_interface", true)
    }
}
