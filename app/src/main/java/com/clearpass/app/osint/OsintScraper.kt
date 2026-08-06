package com.clearpass.app.osint

import com.clearpass.app.sources.SafeSources
import com.clearpass.app.sources.SourceFetcher
import com.clearpass.app.util.LogCollector

object OsintScraper {

    suspend fun scrape(preferWhiteList: Boolean = true): List<String> {
        val primary = if (preferWhiteList) {
            SafeSources.defaultForRfWhiteList()
        } else {
            SafeSources.defaultBlackMobile()
        }
        LogCollector.i("OSINT", "Primary: ${primary.title}")
        var links = SourceFetcher.fetchSource(primary)
        if (links.isNotEmpty()) return links

        for (s in SafeSources.byMode(primary.mode)) {
            if (s.id == primary.id) continue
            links = SourceFetcher.fetchSource(s)
            if (links.isNotEmpty()) return links
        }
        for (s in SafeSources.backups(primary.mode)) {
            links = SourceFetcher.fetchSource(s)
            if (links.isNotEmpty()) {
                LogCollector.i("OSINT", "Backup hit: ${s.title}")
                return links
            }
        }
        return emptyList()
    }
}
