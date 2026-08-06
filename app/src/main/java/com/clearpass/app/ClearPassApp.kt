package com.clearpass.app

import android.app.Application
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.core.CoreProbe
import com.clearpass.app.util.LogCollector
import com.clearpass.app.worker.ConfigUpdateScheduler
import com.clearpass.app.profile.ProfileStore
import com.clearpass.app.data.SubscriptionStore
import com.clearpass.app.sources.SafeSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class ClearPassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            LogCollector.i("App", "ClearPass 0.2.0 starting")
            CoreBootstrap.prepare(this)
            CoreBootstrap.logCoreStatus(this)
            ConfigUpdateScheduler.schedule(this, 3)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ProfileStore(this@ClearPassApp).ensureDefault()
                    val subs = SubscriptionStore(this@ClearPassApp)
                    if (subs.getAll().isEmpty()) {
                        val w = SafeSources.defaultForRfWhiteList().urls.first()
                        val b = SafeSources.defaultBlackMobile().urls.first()
                        subs.add(w)
                        subs.add(b)
                        LogCollector.i("App", "Seeded default curated subscriptions")
                    }
                } catch (_: Exception) {
                }
            }
            thread(name = "core-probe", isDaemon = true) {
                try {
                    CoreProbe.tryVersion(this@ClearPassApp)
                } catch (_: Exception) {
                }
            }
            LogCollector.i(
                "App",
                "Mode: VpnService TUN + sing-box SOCKS (localhost auth required)"
            )
        } catch (_: Exception) {
        }
    }
}
