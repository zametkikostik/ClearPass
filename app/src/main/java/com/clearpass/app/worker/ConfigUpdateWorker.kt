package com.clearpass.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clearpass.app.data.ConfigCacheStore
import com.clearpass.app.data.ManualConfigStore
import com.clearpass.app.data.SettingsStore
import com.clearpass.app.data.SourceMode
import com.clearpass.app.data.SubscriptionStore
import com.clearpass.app.osint.OsintScraper
import com.clearpass.app.sources.SafeSources
import com.clearpass.app.sources.SourceFetcher
import com.clearpass.app.subscription.SubscriptionManager
import com.clearpass.app.tester.ConfigTester
import com.clearpass.app.util.LogCollector

class ConfigUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val mode = SettingsStore(applicationContext).getSourceMode()
            LogCollector.i("Worker", "Auto-refresh mode=$mode")

            val manual = ManualConfigStore(applicationContext).getAll()
            val subUrls = SubscriptionStore(applicationContext).getAll()
            val fromSubs = if (subUrls.isNotEmpty()) {
                SubscriptionManager.fetchFromSubscriptions(subUrls)
            } else emptyList()

            val curated = mutableListOf<String>()
            when (mode) {
                SourceMode.WHITE_ONLY ->
                    curated += SourceFetcher.fetchSource(SafeSources.defaultForRfWhiteList())
                SourceMode.BLACK_ONLY ->
                    curated += SourceFetcher.fetchSource(SafeSources.defaultBlackMobile())
                SourceMode.BOTH -> {
                    curated += SourceFetcher.fetchSource(SafeSources.defaultForRfWhiteList())
                    curated += SourceFetcher.fetchSource(SafeSources.defaultBlackMobile())
                }
            }

            val preferWhite = mode != SourceMode.BLACK_ONLY
            val osint = OsintScraper.scrape(preferWhiteList = preferWhite)

            val all = (manual + fromSubs + curated.distinct() + osint).distinct()
            LogCollector.i("Worker", "Raw links: ${all.size}")

            if (all.isEmpty()) return Result.success()

            val tested = ConfigTester.testLinks(all, maxToTest = 30)
            ConfigCacheStore(applicationContext).saveTested(tested)
            LogCollector.i("Worker", "Alive cached: ${tested.size}")
            Result.success()
        } catch (e: Exception) {
            LogCollector.e("Worker", e.message ?: "fail")
            Result.retry()
        }
    }
}
