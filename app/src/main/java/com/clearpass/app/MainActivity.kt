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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearpass.app.core.CoreBootstrap
import com.clearpass.app.data.SettingsStore
import com.clearpass.app.data.SourceMode
import com.clearpass.app.sources.SafeSources
import com.clearpass.app.stats.SessionStats
import com.clearpass.app.tunnel.HevJni
import com.clearpass.app.tunnel.Tun2SocksBridge
import com.clearpass.app.ui.theme.*
import com.clearpass.app.util.LogCollector
import com.clearpass.app.util.LogEntry
import com.clearpass.app.util.LogLevel
import com.clearpass.app.vpn.ConnectionManager
import com.clearpass.app.vpn.ConnectionState
import com.clearpass.app.worker.ConfigUpdateScheduler
import kotlinx.coroutines.launch

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
                val sourceMode by settingsStore.sourceModeFlow.collectAsState(initial = SourceMode.BOTH)
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
                        "settings" -> SettingsScreen(
                            killSwitch = killSwitch,
                            sourceMode = sourceMode,
                            onKillSwitch = { v -> scope.launch { settingsStore.setKillSwitch(v) } },
                            onSourceMode = { m -> scope.launch { settingsStore.setSourceMode(m) } },
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
        LogCollector.i("Main", "UI ready v0.2.0")
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
    onKillSwitch: (Boolean) -> Unit,
    onSourceMode: (SourceMode) -> Unit,
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

        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefreshSources, modifier = Modifier.fillMaxWidth()) {
            Text("Обновить источники сейчас")
        }
    }
}
