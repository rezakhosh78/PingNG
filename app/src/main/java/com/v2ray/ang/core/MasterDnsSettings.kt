package com.v2ray.ang.core

/** Presents the upstream client configuration as individual fields without losing unknown keys. */
object MasterDnsSettings {
    private val assignment = Regex("^([A-Z][A-Z0-9_]*)\\s*=\\s*(.*)$")
    private val section = Regex("^# [0-9]+\\) (.+)$")
    private val reserved = setOf(
        "DOMAINS", "DATA_ENCRYPTION_METHOD", "ENCRYPTION_KEY",
        "PROTOCOL_TYPE", "LISTEN_IP", "LISTEN_PORT", "MTU_TEST_PARALLELISM",
    )

    data class Field(val key: String, val section: String, val value: String, val kind: Kind)
    enum class Kind { BOOLEAN, STRING, NUMBER }

    fun fields(config: String): List<Field> {
        var group = "Other settings"
        return buildList {
            config.lineSequence().forEach { line ->
                section.matchEntire(line.trim())?.let { group = it.groupValues[1] }
                val match = assignment.matchEntire(line.trim()) ?: return@forEach
                val key = match.groupValues[1]
                if (key in reserved) return@forEach
                val raw = match.groupValues[2].trim()
                val kind = when {
                    raw == "true" || raw == "false" -> Kind.BOOLEAN
                    raw.startsWith('"') && raw.endsWith('"') -> Kind.STRING
                    else -> Kind.NUMBER
                }
                add(Field(key, group, if (kind == Kind.STRING) decode(raw) else raw, kind))
            }
        }
    }

    fun value(config: String, key: String): String? = config.lineSequence()
        .mapNotNull { assignment.matchEntire(it.trim()) }
        .firstOrNull { it.groupValues[1] == key }?.groupValues?.get(2)?.trim()

    fun put(config: String, key: String, value: String, kind: Kind): String {
        require(key.matches(Regex("[A-Z][A-Z0-9_]*")))
        val encoded = if (kind == Kind.STRING) encode(value) else value.trim()
        val replacement = "$key = $encoded"
        var found = false
        val lines = config.lineSequence().map { line ->
            if (assignment.matchEntire(line.trim())?.groupValues?.get(1) == key) {
                found = true
                replacement
            } else line
        }.toList()
        return (if (found) lines else lines + replacement).joinToString("\n")
    }

    fun valid(field: Field): Boolean = when (field.kind) {
        Kind.BOOLEAN -> field.value == "true" || field.value == "false"
        Kind.NUMBER -> field.value.toDoubleOrNull()?.isFinite() == true
        Kind.STRING -> true
    }

    private fun encode(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun decode(raw: String): String = raw.substring(1, raw.length - 1)
        .replace("\\n", "\n").replace("\\r", "\r")
        .replace("\\\"", "\"").replace("\\\\", "\\")
}
