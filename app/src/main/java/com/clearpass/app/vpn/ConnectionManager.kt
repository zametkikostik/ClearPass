package com.clearpass.app.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.clearpass.app.converter.SingBoxConverter
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.data.CachedConfig
import com.clearpass.app.data.ConfigCacheStore
import com.clearpass.app.data.ManualConfigStore
import com.clearpass.app.data.SettingsStore
import com.clearpass.app.data.SourceMode
import com.clearpass.app.data.SubscriptionStore
import com.clearpass.app.osint.OsintScraper
import com.clearpass.app.parser.ProxyLinkParser
import com.clearpass.app.security.UriCipher
import com.clearpass.app.stats.SessionRepository
import com.clearpass.app.stats.SessionStats
import com.clearpass.app.subscription.SubscriptionManager
import com.clearpass.app.tester.ConfigTester
import com.clearpass.app.tester.TestedLink
import com.clearpass.app.util.LogCollector
import com.clearpass.app.whitelist.WhiteListManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val manualStore = ManualConfigStore(context)
    private val subStore = SubscriptionStore(context)
    private val cache = ConfigCacheStore(context)
    private val sessionRepo = SessionRepository(context)
    private val settings = SettingsStore(context)

    private var currentUri: String? = null
    private var healthJob: Job? = null
    private val rotating = AtomicBoolean(false)

    fun connect(prepareLauncher: ((Intent) -> Unit)? = null) {
        scope.launch {
            try {
                _state.value = ConnectionState.Connecting
                CoreBootstrap.prepare(context)

                // Download official sing-box if not packaged in APK
                val coreOk = withContext(Dispatchers.IO) { CoreBootstrap.ensureCore(context) }
                if (!coreOk) {
                    _state.value = ConnectionState.Error(
                        "Нет ядра sing-box. Нужен интернет для первой загрузки (~18 МБ)."
                    )
                    return@launch
                }

                val prepare = VpnService.prepare(context)
                if (prepare != null) {
                    LogCollector.i("CM", "VPN permission required")
                    if (prepareLauncher != null) prepareLauncher(prepare)
                    else if (context is Activity) context.startActivity(prepare)
                    _state.value = ConnectionState.Disconnected
                    return@launch
                }

                val best = withContext(Dispatchers.IO) { resolveBest() }
                if (best == null) {
                    _state.value = ConnectionState.Error(
                        "Нет рабочих конфигов. Добавьте VLESS или subscription."
                    )
                    return@launch
                }

                if (!startWith(best)) {
                    _state.value = ConnectionState.Error("Не удалось запустить туннель")
                }
            } catch (e: Exception) {
                LogCollector.e("CM", "connect: ${e.message}")
                _state.value = ConnectionState.Error(e.message ?: "Ошибка подключения")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            stopHealth()
            stopService()
            currentUri = null
            SessionStats.onDisconnected()
            sessionRepo.finish("user")
            _state.value = ConnectionState.Disconnected
            LogCollector.i("CM", "Disconnected")
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) connect(null)
        else _state.value = ConnectionState.Error("Нет разрешения VPN")
    }

    private suspend fun resolveBest(): TestedLink? {
        val cached = cache.getTop(12)
        if (cached.size >= 3) {
            LogCollector.i("CM", "Using cache (${cached.size})")
            return cached.first().toTested()
        }

        val manual = manualStore.getAll()
        val subUrls = subStore.getAll()
        val fromSubs = if (subUrls.isNotEmpty()) {
            SubscriptionManager.fetchFromSubscriptions(subUrls)
        } else emptyList()

        val sourceMode = settings.getSourceMode()
        var fromOsint = when (sourceMode) {
            SourceMode.BLACK_ONLY -> OsintScraper.scrape(preferWhiteList = false)
            else -> OsintScraper.scrape(preferWhiteList = true)
        }
        var all = (manual + fromSubs + fromOsint).distinct()

        if (all.isEmpty() && sourceMode == SourceMode.BOTH) {
            fromOsint = OsintScraper.scrape(preferWhiteList = false)
            all = (manual + fromSubs + fromOsint).distinct()
        }

        if (all.isEmpty()) {
            if (cached.isNotEmpty()) return cached.first().toTested()
            return null
        }

        val tested = ConfigTester.testLinks(all, maxToTest = 20)
        cache.saveTested(tested)
        return tested.firstOrNull() ?: cached.firstOrNull()?.toTested()
    }

    private suspend fun startWith(best: TestedLink): Boolean {
        val link = ProxyLinkParser.parse(best.uri) ?: return false
        val auth = SingBoxConverter.generateLocalAuth()
        val json = SingBoxConverter.convert(link, auth)

        val intent = Intent(context, ClearPassVpnService::class.java).apply {
            action = ClearPassVpnService.ACTION_CONNECT
            putExtra(ClearPassVpnService.EXTRA_CONFIG, json)
            putExtra(ClearPassVpnService.EXTRA_KILL_SWITCH, settings.isKillSwitch())
            putExtra(ClearPassVpnService.EXTRA_SOCKS_USER, auth.username)
            putExtra(ClearPassVpnService.EXTRA_SOCKS_PASS, auth.password)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            LogCollector.e("CM", "startService: ${e.message}")
            return false
        }

        currentUri = best.uri
        _state.value = ConnectionState.Connected(
            address = best.address,
            sni = best.sni,
            protocol = best.protocol,
            latencyMs = best.latencyMs
        )
        SessionStats.onConnected(best.address, best.sni, best.protocol)
        sessionRepo.start(best.address, best.sni, best.protocol, best.latencyMs)
        startHealth(best.address, best.port)
        LogCollector.i("CM", "Connected ${best.protocol} ${best.address}:${best.port} sni=${best.sni}")
        return true
    }

    private fun startHealth(host: String, port: Int) {
        stopHealth()
        healthJob = scope.launch {
            while (isActive) {
                delay(20_000)
                val ok = HealthMonitor.isServerReachable(host, port)
                if (!ok) {
                    LogCollector.w("CM", "Health fail — rotate")
                    rotate()
                    break
                }
            }
        }
    }

    private fun stopHealth() {
        healthJob?.cancel()
        healthJob = null
    }

    private suspend fun rotate() {
        if (!rotating.compareAndSet(false, true)) return
        try {
            stopService()
            delay(500)
            val top = cache.getTop(15).filter { it.uri != currentUri }
            var ok = false
            for (c in top) {
                if (startWith(c.toTested())) {
                    SessionStats.onRotated(c.address, c.sni, c.protocol)
                    ok = true
                    break
                }
                cache.markFailed(c.uri)
            }
            if (!ok) {
                _state.value = ConnectionState.Error("Ротация не удалась — нет живых серверов")
            }
        } finally {
            rotating.set(false)
        }
    }

    private fun stopService() {
        try {
            val intent = Intent(context, ClearPassVpnService::class.java).apply {
                action = ClearPassVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
        } catch (e: Exception) {
            LogCollector.e("CM", "stopService: ${e.message}")
        }
    }

    suspend fun addManualUri(uri: String): Result<Unit> = withContext(Dispatchers.IO) {
        val parsed = ProxyLinkParser.parse(uri.trim())
            ?: return@withContext Result.failure(IllegalArgumentException("Не VLESS/Hy2/TUIC"))
        val toStore = if (uri.trim().startsWith("vless://", true)) {
            WhiteListManager.injectSni(uri.trim()) ?: uri.trim()
        } else uri.trim()
        if (!manualStore.add(toStore)) {
            return@withContext Result.failure(IllegalArgumentException("Не сохранено"))
        }
        val tested = ConfigTester.testLinks(listOf(toStore), maxToTest = 1)
        cache.saveTested(tested)
        UriCipher.encrypt(context, toStore)
        LogCollector.i("CM", "Manual saved: ${parsed.address}")
        Result.success(Unit)
    }

    suspend fun addSubscription(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!subStore.add(url)) {
            return@withContext Result.failure(IllegalArgumentException("Нужен http(s) URL"))
        }
        Result.success(Unit)
    }

    suspend fun refreshConfigs(): Int = withContext(Dispatchers.IO) {
        val manual = manualStore.getAll()
        val subs = subStore.getAll()
        val fromSubs = if (subs.isNotEmpty()) SubscriptionManager.fetchFromSubscriptions(subs) else emptyList()
        val fromOsint = OsintScraper.scrape()
        val all = (manual + fromSubs + fromOsint).distinct()
        val tested = ConfigTester.testLinks(all, maxToTest = 25)
        cache.saveTested(tested)
        tested.size
    }

    private fun CachedConfig.toTested() = TestedLink(
        uri, address, port, sni, protocol, latencyMs, score
    )
}
