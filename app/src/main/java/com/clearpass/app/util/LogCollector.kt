package com.clearpass.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

object LogCollector {

    private const val MAX_SIZE = 500
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, msg: String) = add(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String) = add(LogLevel.WARN, tag, msg)
    fun e(tag: String, msg: String) = add(LogLevel.ERROR, tag, msg)

    @Synchronized
    private fun add(level: LogLevel, tag: String, message: String) {
        try {
            val entry = LogEntry(
                time = timeFormat.format(Date()),
                level = level,
                tag = tag,
                message = message.take(2000)
            )
            entries.add(entry)
            while (entries.size > MAX_SIZE) {
                entries.removeAt(0)
            }
            _flow.value = entries.toList()
        } catch (_: Exception) {
        }
    }

    fun clear() {
        entries.clear()
        _flow.value = emptyList()
    }
}
