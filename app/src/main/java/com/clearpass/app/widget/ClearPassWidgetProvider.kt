package com.clearpass.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.clearpass.app.R
import com.clearpass.app.vpn.ClearPassVpnService

class ClearPassWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id, false, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            try {
                val svc = Intent(context, ClearPassVpnService::class.java).apply {
                    action = ClearPassVpnService.ACTION_DISCONNECT
                }
                context.startService(svc)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.clearpass.WIDGET_TOGGLE"

        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            connected: Boolean,
            server: String?
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_clearpass)
                views.setTextViewText(
                    R.id.widget_status,
                    if (connected) "CONNECTED" else "DISCONNECTED"
                )
                views.setTextViewText(R.id.widget_server, server ?: "—")
                views.setTextViewText(R.id.widget_button, if (connected) "STOP" else "START")

                val toggle = Intent(context, ClearPassWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE
                }
                val pi = PendingIntent.getBroadcast(
                    context, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_button, pi)
                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {
            }
        }
    }
}
