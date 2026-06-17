package com.aipay.listener.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,   // I / W / E / D
    val tag: String,
    val message: String
)

object DebugLog {
    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append(LogEntry(level = "I", tag = tag, message = msg))
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append(LogEntry(level = "W", tag = tag, message = msg))
    }

    @Synchronized
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        append(LogEntry(level = "E", tag = tag, message = if (throwable != null) "$msg | ${throwable.message}" else msg))
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    @Synchronized
    fun allText(): String {
        return _entries.value.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            "[$time] ${entry.level}/${entry.tag} ${entry.message}"
        }
    }

    private fun append(entry: LogEntry) {
        val list = _entries.value.toMutableList()
        list.add(entry)
        if (list.size > MAX_ENTRIES) {
            list.removeAt(0)
        }
        _entries.value = list
    }
}
