package com.clearpass.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clearpass.app.data.SettingsStore
import com.clearpass.app.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Автозапуск VPN после загрузки устройства.
 *
 * Поднимает туннель, только если в настройках включено «Автоподключение».
 * Право VPN должно быть уже выдано пользователем (система помнит согласие),
 * иначе подключение просто завершится ошибкой в логах — показать диалог
 * разрешения из ресивера нельзя.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                if (SettingsStore(appContext).isAutoConnectOnBoot()) {
                    LogCollector.i("Boot", "BOOT_COMPLETED — auto connect")
                    ConnectionManager(appContext).connect()
                } else {
                    LogCollector.i("Boot", "BOOT_COMPLETED — autoconnect off")
                }
            } catch (e: Exception) {
                LogCollector.e("Boot", e.message ?: "fail")
            } finally {
                pending.finish()
            }
        }
    }
}
