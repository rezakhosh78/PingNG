package com.v2ray.ang.ui.logcat

internal data class LogcatEntry(val key: String, val text: String)

/** Creates stable occurrence keys while retaining each original line and its order. */
internal fun createLogcatEntries(logs: List<String>): List<LogcatEntry> {
    val occurrencesFromNewest = HashMap<String, Int>()
    return logs.asReversed().map { line ->
        val occurrence = occurrencesFromNewest[line] ?: 0
        occurrencesFromNewest[line] = occurrence + 1
        LogcatEntry(key = "$occurrence:$line", text = line)
    }.asReversed()
}
