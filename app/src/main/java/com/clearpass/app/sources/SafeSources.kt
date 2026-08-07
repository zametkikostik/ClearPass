package com.clearpass.app.sources

/**
 * Curated public subscription catalogs (NOT a full-internet crawler).
 * Primary: igareck/vpn-configs-for-russia
 * Backup: Stintik-123, nikita29a/FreeProxyList + new mirrors
 */
object SafeSources {

    enum class Mode {
        WHITE_LIST_BYPASS,
        BLACK_LIST_MOBILE,
        BLACK_LIST_FULL
    }

    data class Source(
        val id: String,
        val title: String,
        val mode: Mode,
        val urls: List<String>,
        val priority: Int = 0  // higher = more trusted
    )

    private fun igareck(file: String): List<String> {
        val repo = "igareck/vpn-configs-for-russia"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githack.com/$repo/$b/$file",
            "https://gitlab.com/$repo/-/raw/$b/$file",
            "https://codeberg.org/$repo/raw/branch/$b/$file",
            "https://bitbucket.org/$repo/raw/$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file"
        )
    }

    private fun stintik(file: String): List<String> {
        val repo = "Stintik-123/vpn-configs-russia"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file"
        )
    }

    private fun freeProxy(file: String): List<String> {
        val repo = "nikita29a/FreeProxyList"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file"
        )
    }

    private fun yanaKh(file: String): List<String> {
        val repo = "yana-kh/vless-reality"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file",
            "https://raw.githack.com/$repo/$b/$file"
        )
    }

    private fun mahdiGhm(file: String): List<String> {
        val repo = "MahdiGHM/V2rayConfigs"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file"
        )
    }

    private fun freeV2ray(file: String): List<String> {
        val repo = "free-v2ray/configs"
        val b = "main"
        return listOf(
            "https://gitlab.com/$repo/-/raw/$b/$file",
            "https://cdn.jsdelivr.net/gh/free-v2ray/all@$b/$file",
            "https://raw.githubusercontent.com/free-v2ray/all/$b/$file"
        )
    }

    private fun v2rayTeam(file: String): List<String> {
        val repo = "v2ray-team/v2ray-configs"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file"
        )
    }

    private fun proxyHub(file: String): List<String> {
        val repo = "proxyhub-io/free-proxy"
        val b = "main"
        return listOf(
            "https://cdn.jsdelivr.net/gh/$repo@$b/$file",
            "https://raw.githubusercontent.com/$repo/$b/$file",
            "https://gitlab.com/proxyhub-io/free-proxy/-/raw/$b/$file"
        )
    }

    val catalog: List<Source> = listOf(
        // Igareck - основной источник (приоритет 10)
        Source("white_mobile", "igareck · White Reality mobile", Mode.WHITE_LIST_BYPASS, igareck("Vless-Reality-White-Lists-Rus-Mobile.txt"), 10),
        Source("white_mobile_2", "igareck · White Reality mobile #2", Mode.WHITE_LIST_BYPASS, igareck("Vless-Reality-White-Lists-Rus-Mobile-2.txt"), 10),
        Source("white_cidr_checked", "igareck · WHITE CIDR checked", Mode.WHITE_LIST_BYPASS, igareck("WHITE-CIDR-RU-checked.txt"), 10),
        Source("white_cidr_all", "igareck · WHITE CIDR all", Mode.WHITE_LIST_BYPASS, igareck("WHITE-CIDR-RU-all.txt"), 9),
        Source("white_sni", "igareck · WHITE SNI", Mode.WHITE_LIST_BYPASS, igareck("WHITE-SNI-RU-all.txt"), 9),
        Source("black_mobile", "igareck · BLACK VLESS mobile", Mode.BLACK_LIST_MOBILE, igareck("BLACK_VLESS_RUS_mobile.txt"), 10),
        Source("black_full", "igareck · BLACK VLESS full", Mode.BLACK_LIST_FULL, igareck("BLACK_VLESS_RUS.txt"), 9),
        
        // Stintik - бекaп (приоритет 7)
        Source("stintik_mobile", "Stintik · mobile", Mode.BLACK_LIST_MOBILE, stintik("mobile.txt"), 7),
        Source("stintik_alive", "Stintik · alive", Mode.BLACK_LIST_FULL, stintik("alive.txt"), 7),
        
        // FreeProxyList - бекaп (приоритет 6)
        Source("freeproxy_m1", "FreeProxyList · mirror1", Mode.BLACK_LIST_MOBILE, freeProxy("mirror/1.txt"), 6),
        Source("freeproxy_m2", "FreeProxyList · mirror2", Mode.BLACK_LIST_MOBILE, freeProxy("mirror/2.txt"), 6),
        
        // Yana-kh - дополнительный источник белых списков (приоритет 8)
        Source("yanakh_white", "yana-kh · VLESS Reality white", Mode.WHITE_LIST_BYPASS, yanaKh("white-list.txt"), 8),
        Source("yanakh_mobile", "yana-kh · VLESS mobile", Mode.BLACK_LIST_MOBILE, yanaKh("mobile.txt"), 7),
        
        // MahdiGHM - configs для Ирана/РФ (приоритет 7)
        Source("mahdi_all", "MahdiGHM · all configs", Mode.BLACK_LIST_FULL, mahdiGhm("all.txt"), 7),
        Source("mahdi_mobile", "MahdiGHM · mobile optimized", Mode.BLACK_LIST_MOBILE, mahdiGhm("mobile.txt"), 7),
        
        // FreeV2ray - Telegram-каналы (приоритет 6)
        Source("freev2ray_daily", "FreeV2ray · daily", Mode.BLACK_LIST_FULL, freeV2ray("daily.txt"), 6),
        Source("freev2ray_hourly", "FreeV2ray · hourly", Mode.BLACK_LIST_MOBILE, freeV2ray("hourly.txt"), 6),
        
        // V2rayTeam - резерв (приоритет 5)
        Source("v2rayteam_main", "V2rayTeam · main", Mode.BLACK_LIST_FULL, v2rayTeam("configs.txt"), 5),
        
        // ProxyHub - агрегатор (приоритет 5)
        Source("proxyhub_vless", "ProxyHub · VLESS", Mode.BLACK_LIST_MOBILE, proxyHub("vless.txt"), 5),
        Source("proxyhub_reality", "ProxyHub · Reality", Mode.WHITE_LIST_BYPASS, proxyHub("reality.txt"), 5)
    )

    fun defaultForRfWhiteList(): Source = catalog.first { it.id == "white_mobile" }
    fun defaultBlackMobile(): Source = catalog.first { it.id == "black_mobile" }
    fun byMode(mode: Mode): List<Source> = catalog.filter { it.mode == mode }.sortedByDescending { it.priority }

    fun backups(mode: Mode): List<Source> = when (mode) {
        Mode.WHITE_LIST_BYPASS -> catalog.filter { it.id in setOf("white_mobile_2", "white_cidr_checked", "white_sni", "yanakh_white", "proxyhub_reality") }
        Mode.BLACK_LIST_MOBILE -> catalog.filter { it.id in setOf("stintik_mobile", "freeproxy_m1", "freeproxy_m2", "yanakh_mobile", "mahdi_mobile", "freev2ray_hourly", "proxyhub_vless") }
        Mode.BLACK_LIST_FULL -> catalog.filter { it.id in setOf("black_full", "stintik_alive", "mahdi_all", "freev2ray_daily", "v2rayteam_main") }
    }
    
    fun getById(id: String): Source? = catalog.find { it.id == id }
}
