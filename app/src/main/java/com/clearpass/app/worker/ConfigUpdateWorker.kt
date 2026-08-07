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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class ConfigUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val settingsStore = SettingsStore(applicationContext)
            // Используем эффективный режим с учётом AUTO
            val mode = settingsStore.getEffectiveSourceMode()
            LogCollector.i("Worker", "Auto-refresh effective mode=$mode")

            val manual = ManualConfigStore(applicationContext).getAll()
            val subUrls = SubscriptionStore(applicationContext).getAll()
            val fromSubs = if (subUrls.isNotEmpty()) {
                SubscriptionManager.fetchFromSubscriptions(subUrls)
            } else emptyList()

            // Загружаем из нескольких источников параллельно для надёжности
            val curatedJobs = when (mode) {
                SourceMode.WHITE_ONLY -> {
                    listOf(
                        async { SourceFetcher.fetchSource(SafeSources.defaultForRfWhiteList()) },
                        async { 
                            SafeSources.byMode(SafeSources.Mode.WHITE_LIST_BYPASS)
                                .take(3)
                                .flatMap { SourceFetcher.fetchSource(it) }
                        }
                    )
                }
                SourceMode.BLACK_ONLY -> {
                    listOf(
                        async { SourceFetcher.fetchSource(SafeSources.defaultBlackMobile()) },
                        async {
                            SafeSources.byMode(SafeSources.Mode.BLACK_LIST_MOBILE)
                                .take(3)
                                .flatMap { SourceFetcher.fetchSource(it) }
                        }
                    )
                }
                SourceMode.BOTH -> {
                    listOf(
                        async { SourceFetcher.fetchSource(SafeSources.defaultForRfWhiteList()) },
                        async { SourceFetcher.fetchSource(SafeSources.defaultBlackMobile()) },
                        async {
                            SafeSources.backups(SafeSources.Mode.WHITE_LIST_BYPASS)
                                .take(2)
                                .flatMap { SourceFetcher.fetchSource(it) }
                        },
                        async {
                            SafeSources.backups(SafeSources.Mode.BLACK_LIST_MOBILE)
                                .take(2)
                                .flatMap { SourceFetcher.fetchSource(it) }
                        }
                    )
                }
                SourceMode.AUTO -> {
                    // В AUTO-режиме загружаем оба типа, но приоритет зависит от времени суток
                    listOf(
                        async { SourceFetcher.fetchSource(SafeSources.defaultForRfWhiteList()) },
                        async { SourceFetcher.fetchSource(SafeSources.defaultBlackMobile()) }
                    )
                }
            }

            val curatedResults = curatedJobs.awaitAll()
            val curated = curatedResults.flatten().distinct()
            LogCollector.i("Worker", "Curated sources: ${curated.size} links")

            val preferWhite = mode != SourceMode.BLACK_ONLY
            val osint = OsintScraper.scrape(preferWhiteList = preferWhite)
            LogCollector.i("Worker", "OSINT: ${osint.size} links")

            val all = (manual + fromSubs + curated + osint).distinct()
            LogCollector.i("Worker", "Raw links: ${all.size}")

            if (all.isEmpty()) {
                LogCollector.w("Worker", "No links found, keeping old cache")
                return Result.success()
            }

            // Тестируем больше конфигов для лучшего выбора
            val tested = ConfigTester.testLinks(all, maxToTest = 50)
            ConfigCacheStore(applicationContext).saveTested(tested)
            LogCollector.i("Worker", "Alive cached: ${tested.size}")
            
            if (tested.isNotEmpty()) {
                val avgLatency = tested.map { it.latencyMs }.average().toInt()
                val minLatency = tested.minOf { it.latencyMs }
                LogCollector.i("Worker", "Latency: avg=$avgLatency ms, min=$minLatency ms")
            }
            
            Result.success()
        } catch (e: Exception) {
            LogCollector.e("Worker", e.message ?: "fail")
            Result.retry()
        }
    }
}
