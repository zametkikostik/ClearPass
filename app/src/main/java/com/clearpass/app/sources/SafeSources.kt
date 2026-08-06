package com.clearpass.app.sources

/**
 * Curated public subscription catalogs (NOT a full-internet crawler).
 * Primary: igareck/vpn-configs-for-russia
 * Backup: Stintik-123, nikita29a/FreeProxyList
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
        val urls: List<String>
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

    val catalog: List<Source> = listOf(
        Source("white_mobile", "igareck · White Reality mobile", Mode.WHITE_LIST_BYPASS, igareck("Vless-Reality-White-Lists-Rus-Mobile.txt")),
        Source("white_mobile_2", "igareck · White Reality mobile #2", Mode.WHITE_LIST_BYPASS, igareck("Vless-Reality-White-Lists-Rus-Mobile-2.txt")),
        Source("white_cidr_checked", "igareck · WHITE CIDR checked", Mode.WHITE_LIST_BYPASS, igareck("WHITE-CIDR-RU-checked.txt")),
        Source("white_cidr_all", "igareck · WHITE CIDR all", Mode.WHITE_LIST_BYPASS, igareck("WHITE-CIDR-RU-all.txt")),
        Source("white_sni", "igareck · WHITE SNI", Mode.WHITE_LIST_BYPASS, igareck("WHITE-SNI-RU-all.txt")),
        Source("black_mobile", "igareck · BLACK VLESS mobile", Mode.BLACK_LIST_MOBILE, igareck("BLACK_VLESS_RUS_mobile.txt")),
        Source("black_full", "igareck · BLACK VLESS full", Mode.BLACK_LIST_FULL, igareck("BLACK_VLESS_RUS.txt")),
        Source("stintik_mobile", "Stintik · mobile", Mode.BLACK_LIST_MOBILE, stintik("mobile.txt")),
        Source("stintik_alive", "Stintik · alive", Mode.BLACK_LIST_FULL, stintik("alive.txt")),
        Source("freeproxy_m1", "FreeProxyList · mirror1", Mode.BLACK_LIST_MOBILE, freeProxy("mirror/1.txt")),
        Source("freeproxy_m2", "FreeProxyList · mirror2", Mode.BLACK_LIST_MOBILE, freeProxy("mirror/2.txt"))
    )

    fun defaultForRfWhiteList(): Source = catalog.first { it.id == "white_mobile" }
    fun defaultBlackMobile(): Source = catalog.first { it.id == "black_mobile" }
    fun byMode(mode: Mode): List<Source> = catalog.filter { it.mode == mode }

    fun backups(mode: Mode): List<Source> = when (mode) {
        Mode.WHITE_LIST_BYPASS -> catalog.filter { it.id in setOf("white_mobile_2", "white_cidr_checked", "white_sni") }
        Mode.BLACK_LIST_MOBILE -> catalog.filter { it.id in setOf("stintik_mobile", "freeproxy_m1", "freeproxy_m2") }
        Mode.BLACK_LIST_FULL -> catalog.filter { it.id in setOf("black_full", "stintik_alive") }
    }
}
