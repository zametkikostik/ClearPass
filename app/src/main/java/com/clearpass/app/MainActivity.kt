package com.clearpass.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.data.SettingsStore
import com.clearpass.app.data.SourceMode
import com.clearpass.app.data.SplitTunnelMode
import com.clearpass.app.sources.SafeSources
import com.clearpass.app.stats.SessionStats
import com.clearpass.app.tunnel.HevJni
import com.clearpass.app.tunnel.Tun2SocksBridge
import com.clearpass.app.ui.theme.*
import com.clearpass.app.updater.SelfUpdater
import com.clearpass.app.util.LogCollector
import com.clearpass.app.util.LogEntry
import com.clearpass.app.util.LogLevel
import com.clearpass.app.vpn.ConnectionManager
import com.clearpass.app.vpn.ConnectionState
import com.clearpass.app.worker.ConfigUpdateScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String)

private fun splitModeLabel(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.ALL -> "Весь трафик через VPN"
    SplitTunnelMode.INCLUDE -> "Только выбранные приложения"
    SplitTunnelMode.EXCLUDE -> "Кроме выбранных приложений"
}

class MainActivity : ComponentActivity() {

    private lateinit var connectionManager: ConnectionManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        connectionManager.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionManager = ConnectionManager(applicationContext)

        setContent {
            ClearPassTheme {
                val state by connectionManager.state.collectAsState()
                val logs by LogCollector.flow.collectAsState()
                val session by SessionStats.session.collectAsState()
                val settingsStore = remember { SettingsStore(applicationContext) }
                val killSwitch by settingsStore.killSwitchFlow.collectAsState(initial = true)
                val autoConnect by settingsStore.autoConnectOnBootFlow.collectAsState(initial = false)
                val sourceMode by settingsStore.sourceModeFlow.collectAsState(initial = SourceMode.BOTH)
                val splitMode by settingsStore.splitModeFlow.collectAsState(initial = SplitTunnelMode.ALL)
                val splitPackages by settingsStore.splitPackagesFlow.collectAsState(initial = emptySet())
                var screen by remember { mutableStateOf("main") }
                val scope = rememberCoroutineScope()
                val bridgeMode = Tun2SocksBridge.currentMode()

                Surface(Modifier.fillMaxSize(), color = CyberBlack) {
                    when (screen) {
                        "logs" -> LogsContent(logs = logs, onBack = { screen = "main" })
                        "add" -> AddConfigScreen(
                            onBack = { screen = "main" },
                            onAddUri = { uri ->
                                scope.launch {
                                    connectionManager.addManualUri(uri)
                                        .onSuccess { LogCollector.i("UI", "URI saved") }
                                        .onFailure { LogCollector.e("UI", it.message ?: "fail") }
                                }
                            },
                            onAddSub = { url ->
                                scope.launch {
                                    connectionManager.addSubscription(url)
                                        .onSuccess { LogCollector.i("UI", "Subscription saved") }
                                        .onFailure { LogCollector.e("UI", it.message ?: "fail") }
                                }
                            }
                        )
                        "split" -> SplitTunnelScreen(
                            initialMode = splitMode,
                            initialSelected = splitPackages,
                            onSave = { mode, pkgs ->
                                scope.launch {
                                    settingsStore.setSplitMode(mode)
                                    settingsStore.setSplitPackages(pkgs)
                                    LogCollector.i("UI", "Split saved: $mode, ${pkgs.size} apps")
                                }
                            },
                            onBack = { screen = "settings" }
                        )
                        "settings" -> SettingsScreen(
                            killSwitch = killSwitch,
                            sourceMode = sourceMode,
                            autoConnect = autoConnect,
                            splitMode = splitMode,
                            onAutoConnect = { v -> scope.launch { settingsStore.setAutoConnectOnBoot(v) } },
                            onKillSwitch = { v -> scope.launch { settingsStore.setKillSwitch(v) } },
                            onSourceMode = { m -> scope.launch { settingsStore.setSourceMode(m) } },
                            onOpenSplit = { screen = "split" },
                            onRefreshSources = {
                                ConfigUpdateScheduler.refreshNow(applicationContext)
                                LogCollector.i("UI", "Refresh sources requested")
                            },
                            onBack = { screen = "main" }
                        )
                        else -> MainContent(
                            state = state,
                            hasCore = CoreBootstrap.hasBinary(applicationContext),
                            bridgeMode = bridgeMode,
                            jniLib = HevJni.available,
                            sessionRotations = session.rotations,
                            onConnect = {
                                connectionManager.connect { intent ->
                                    vpnPermissionLauncher.launch(intent)
                                }
                            },
                            onDisconnect = { connectionManager.disconnect() },
                            onOpenLogs = { screen = "logs" },
                            onOpenAdd = { screen = "add" },
                            onOpenSettings = { screen = "settings" }
                        )
                    }
                }
            }
        }
        LogCollector.i("Main", "UI ready v0.3.0")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(
    state: ConnectionState,
    hasCore: Boolean,
    bridgeMode: String,
    jniLib: Boolean,
    sessionRotations: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAdd: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isConnected = state is ConnectionState.Connected
    val isConnecting = state is ConnectionState.Connecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClearPass", fontWeight = FontWeight.Bold, color = NeonGreen) },
                actions = {
                    IconButton(onClick = onOpenAdd) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = NeonCyan)
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs", tint = NeonYellow)
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Set", color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberDark)
            )
        },
        containerColor = CyberBlack
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusBadge(state)
            Spacer(Modifier.height(24.dp))

            if (state is ConnectionState.Connected) {
                InfoBlock("Server", state.address, NeonCyan)
                InfoBlock("SNI", state.sni, NeonGreen)
                InfoBlock("Protocol", state.protocol)
                if (state.latencyMs >= 0) InfoBlock("Latency", "${state.latencyMs} ms")
                if (sessionRotations > 0) InfoBlock("Rotations", "$sessionRotations")
            }

            if (!hasCore) {
                Spacer(Modifier.height(12.dp))
                Text("SAFE MODE: libsingbox.so missing", color = NeonYellow, fontSize = 12.sp)
            }
            Text(
                "Tun2Socks: $bridgeMode · JNI: ${if (jniLib) "yes" else "no"}",
                color = TextMuted,
                fontSize = 11.sp
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { if (isConnected || isConnecting) onDisconnect() else onConnect() },
                modifier = Modifier.size(160.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isConnected -> NeonPink
                        isConnecting -> NeonYellow
                        else -> NeonGreen
                    },
                    contentColor = Color.Black
                )
            ) {
                Text(
                    when {
                        isConnected -> "STOP"
                        isConnecting -> "…"
                        else -> "START"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AddConfigScreen(
    onBack: () -> Unit,
    onAddUri: (String) -> Unit,
    onAddSub: (String) -> Unit
) {
    var uri by remember { mutableStateOf("") }
    var sub by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("< Back", color = NeonCyan) }
        Text("Добавить конфиг", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = uri,
            onValueChange = { uri = it },
            label = { Text("vless:// / hy2:// / tuic://") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (uri.isNotBlank()) {
                    onAddUri(uri)
                    message = "URI добавлен"
                    uri = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить URI") }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = sub,
            onValueChange = { sub = it },
            label = { Text("Subscription URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (sub.isNotBlank()) {
                    onAddSub(sub)
                    message = "Subscription добавлен"
                    sub = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Добавить subscription") }

        Spacer(Modifier.height(16.dp))
        Text("Курируемые источники", color = NeonYellow, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onAddSub(SafeSources.defaultForRfWhiteList().urls.first())
                message = "Добавлен: белые списки mobile"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Белые списки · mobile") }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                onAddSub(SafeSources.defaultBlackMobile().urls.first())
                message = "Добавлен: чёрные списки mobile"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Чёрные списки · mobile") }

        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = NeonGreen)
        }
    }
}

/**
 * Экран split tunneling: выбор режима и приложений (только запускаемые,
 * без системного мусора). Список грузится в фоне, сохранение — по кнопке.
 */
@Composable
private fun SplitTunnelScreen(
    initialMode: SplitTunnelMode,
    initialSelected: Set<String>,
    onSave: (SplitTunnelMode, Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(initialMode) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    initialSelected.forEach { selected[it] = true }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val pm = ctx.packageManager
                pm.getInstalledApplications(0)
                    .filter { it.enabled && pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { AppEntry(it.packageName, it.loadLabel(pm).toString()) }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("< Back", color = NeonCyan) }
        Text("Split tunneling", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))

        listOf(
            SplitTunnelMode.ALL to "Весь трафик через VPN",
            SplitTunnelMode.INCLUDE to "Только выбранные",
            SplitTunnelMode.EXCLUDE to "Кроме выбранных"
        ).forEach { (m, label) ->
            val sel = mode == m
            OutlinedButton(
                onClick = { mode = m },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (sel) NeonGreen else Color(0xFF333333))
            ) {
                Text(label, color = if (sel) NeonGreen else TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
        }

        Text("Применяется при следующем подключении", color = TextMuted, fontSize = 11.sp)

        if (mode != SplitTunnelMode.ALL) {
            Spacer(Modifier.height(12.dp))
            Text("Выбрано: ${selected.count { it.value }}", color = NeonCyan, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (apps.isEmpty()) {
                Text("Загружаем список приложений…", color = TextMuted, fontSize = 12.sp)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(apps, key = { it.pkg }) { app ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            app.label,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Checkbox(
                            checked = selected[app.pkg] == true,
                            onCheckedChange = { checked -> selected[app.pkg] = checked }
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                onSave(mode, selected.filter { it.value }.keys)
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
    }
}

@Composable
private fun StatusBadge(state: ConnectionState) {
    val (label, color) = when (state) {
        is ConnectionState.Connected -> "CONNECTED" to NeonGreen
        is ConnectionState.Connecting -> "CONNECTING" to NeonYellow
        is ConnectionState.Error -> "ERROR" to ErrorRed
        else -> "OFFLINE" to TextMuted
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
    if (state is ConnectionState.Error) {
        Spacer(Modifier.height(8.dp))
        Text(state.message, color = ErrorRed, fontSize = 13.sp)
    }
}

@Composable
private fun InfoBlock(title: String, value: String, accent: Color = TextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = TextMuted, fontSize = 13.sp)
        Text(value, color = accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LogsContent(logs: List<LogEntry>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("< Back", color = NeonCyan) }
            TextButton(onClick = { LogCollector.clear() }) { Text("Clear", color = NeonPink) }
        }
        Text("Terminal", color = NeonGreen, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(logs.reversed()) { e ->
                val c = when (e.level) {
                    LogLevel.ERROR -> ErrorRed
                    LogLevel.WARN -> NeonYellow
                    LogLevel.INFO -> NeonGreen
                    else -> TextMuted
                }
                Text(
                    "${e.time} [${e.tag}] ${e.message}",
                    color = c,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    killSwitch: Boolean,
    sourceMode: SourceMode,
    autoConnect: Boolean,
    splitMode: SplitTunnelMode,
    onAutoConnect: (Boolean) -> Unit,
    onKillSwitch: (Boolean) -> Unit,
    onSourceMode: (SourceMode) -> Unit,
    onOpenSplit: () -> Unit,
    onRefreshSources: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("< Back", color = NeonCyan) }
        Text("Настройки", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))

        Text("Источники (igareck)", color = NeonYellow, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        listOf(
            SourceMode.WHITE_ONLY to "Только белые (обход WL)",
            SourceMode.BLACK_ONLY to "Только чёрные",
            SourceMode.BOTH to "Оба"
        ).forEach { (mode, label) ->
            val selected = sourceMode == mode
            OutlinedButton(
                onClick = { onSourceMode(mode) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (selected) NeonGreen else Color(0xFF333333))
            ) {
                Text(label, color = if (selected) NeonGreen else TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Kill-switch", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Держать TUN при падении ядра", color = TextMuted, fontSize = 12.sp)
            }
            Switch(checked = killSwitch, onCheckedChange = onKillSwitch)
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Автоподключение", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Поднимать VPN после загрузки телефона", color = TextMuted, fontSize = 12.sp)
            }
            Switch(checked = autoConnect, onCheckedChange = onAutoConnect)
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Split tunneling", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(splitModeLabel(splitMode), color = TextMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onOpenSplit) { Text("Изменить", color = NeonCyan) }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefreshSources, modifier = Modifier.fillMaxWidth()) {
            Text("Обновить источники сейчас")
        }

        Spacer(Modifier.height(24.dp))
        Text("Обновление приложения", color = NeonYellow, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        UpdateSection()
    }
}

/**
 * Проверка и установка обновления из GitHub Releases.
 * Проверка запускается при входе в настройки, установка — по кнопке.
 */
@Composable
private fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SelfUpdater.UpdateInfo?>(null) }
    var status by remember { mutableStateOf("checking") } // checking | none | ready | downloading | error
    var progress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentVersion = remember { SelfUpdater.currentVersion(ctx) }

    fun recheck() {
        status = "checking"
        scope.launch {
            info = SelfUpdater.checkForUpdate(ctx)
            status = if (info == null) "none" else "ready"
        }
    }

    LaunchedEffect(Unit) { recheck() }

    when (status) {
        "checking" -> {
            Text("Проверяем обновления…", color = TextMuted, fontSize = 12.sp)
        }
        "none" -> {
            Text("Установлена последняя версия ($currentVersion)", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { recheck() }) { Text("Проверить снова") }
        }
        "ready" -> {
            info?.let { i ->
                Text("Доступна версия ${i.version} (сейчас $currentVersion)", color = NeonGreen, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        status = "downloading"
                        progress = 0
                        scope.launch {
                            val ok = SelfUpdater.downloadAndInstall(ctx, i) { pct -> progress = pct }
                            if (!ok) {
                                status = "error"
                                error = "Не удалось скачать APK — подробности в логах"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Скачать и установить") }
                Text(
                    "Размер: ${"%.1f".format(i.sizeBytes / 1024.0 / 1024.0)} МБ",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
        "downloading" -> {
            Text("Скачиваем APK… $progress%", color = NeonCyan, fontSize = 12.sp)
        }
        "error" -> {
            Text(error ?: "Ошибка обновления", color = ErrorRed, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { recheck() }) { Text("Повторить") }
        }
    }
}
