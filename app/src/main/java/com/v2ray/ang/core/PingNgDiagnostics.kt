package com.v2ray.ang.core

import com.v2ray.ang.handler.MmkvManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PingNgDiagnostics {
    private const val MAX_ENTRIES = 500
    private const val STORAGE_KEY = "PINGNG_DIAGNOSTIC_LOG"

    @Synchronized
    fun record(message: String, error: Throwable? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val detail = error?.let(::errorSummary).orEmpty()
        val entries = (snapshot() + "[$timestamp] EVENT $message$detail").takeLast(MAX_ENTRIES)
        MmkvManager.encodeSettings(STORAGE_KEY, entries.joinToString("\n"))
    }

    @Synchronized
    fun recordLog(priority: Int, tag: String, message: String, error: Throwable? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val level = when (priority) {
            android.util.Log.ERROR -> "ERROR"
            android.util.Log.WARN -> "WARN"
            android.util.Log.INFO -> "INFO"
            android.util.Log.DEBUG -> "DEBUG"
            else -> "VERBOSE"
        }
        val detail = error?.let(::errorSummary).orEmpty()
        val cleanMessage = message.replace('\n', ' ').replace('\r', ' ')
        val entries = (snapshot() + "[$timestamp] $level/$tag $cleanMessage$detail")
            .takeLast(MAX_ENTRIES)
        MmkvManager.encodeSettings(STORAGE_KEY, entries.joinToString("\n"))
    }

    private fun errorSummary(error: Throwable): String {
        val location = error.stackTrace.firstOrNull()?.let {
            " @ ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }.orEmpty()
        return " | ${error.javaClass.simpleName}: ${error.message.orEmpty()}$location"
    }

    fun snapshot(): List<String> = MmkvManager
        .decodeSettingsString(STORAGE_KEY)
        .orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .toList()

    fun clear() {
        MmkvManager.encodeSettings(STORAGE_KEY, null)
    }
}
