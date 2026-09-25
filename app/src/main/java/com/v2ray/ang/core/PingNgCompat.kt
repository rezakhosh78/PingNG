package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Pure configuration helpers for PingNG's embedded Android Desync engine. */
object PingNgCompat {
    const val PROFILE_OFF = "Off"
    const val PROFILE_LIGHT = "Light"
    const val PROFILE_BALANCED = "Balanced"
    const val PROFILE_SEVERE = "Severe"
    const val PROFILE_ADAPTIVE = "Adaptive"
    const val PROFILE_CUSTOM = "Custom"

    const val METHOD_SPLIT = "Split"
    const val METHOD_DISORDER = "Disorder"
    const val METHOD_FAKE_SNI = "Fake SNI"
    const val METHOD_OUT_OF_BAND = "Out of Band"
    const val METHOD_DISORDER_OUT_OF_BAND = "Disorder + Out of Band"

    fun supportsNativeDesync(profileItem: ProfileItem): Boolean = when (profileItem.configType) {
        EConfigType.VMESS,
        EConfigType.VLESS,
        EConfigType.SHADOWSOCKS,
        EConfigType.SOCKS,
        EConfigType.HTTP,
        EConfigType.TROJAN,
        EConfigType.WARP -> true
        else -> false
    }

    fun isNativeDesyncEnabled(profileItem: ProfileItem): Boolean =
        supportsNativeDesync(profileItem) &&
            normalizeProfile(profileItem.pingNgProfile) != PROFILE_OFF

    fun getPresetArguments(profile: String): String {
        return when (normalizeProfile(profile)) {
            PROFILE_LIGHT -> "--proto=tls --split 1+s --tlsrec 1+s --delay-range 0-1"
            PROFILE_BALANCED -> "--proto=tls --split-range 1-3+s --tlsrec 1-3+s --delay-range 1-3"
            PROFILE_SEVERE -> "--proto=tls --split 1+s --tlsrec 1+s --disorder 3+s --fake -1 --ttl 8 --fake-jitter --delay-range 1-5"
            PROFILE_ADAPTIVE -> "--proto=tls --split-range 1-3+s --tlsrec 1-3+s --auto=torst,redirect,ssl_err --disorder 1 --tlsrec 1-3+s --auto=torst,redirect,ssl_err --fake -1 --ttl-range 7-10 --fake-jitter --cache-ttl 3600 --delay-range 1-5"
            else -> ""
        }
    }

    fun buildCommandLine(profileItem: ProfileItem, port: Int): List<String>? {
        if (!isNativeDesyncEnabled(profileItem) || port !in 1..65535) return null
        val strategy = when (normalizeProfile(profileItem.pingNgProfile)) {
            PROFILE_LIGHT -> listOf(
                "--proto=tls", "--split", "1+s", "--tlsrec", "1+s", "--delay-range", "0-1"
            )
            PROFILE_BALANCED -> listOf(
                "--proto=tls", "--split-range", "1-3+s", "--tlsrec", "1-3+s",
                "--delay-range", "1-3"
            )
            PROFILE_SEVERE -> listOf(
                "--proto=tls", "--split", "1+s", "--tlsrec", "1+s", "--disorder", "3+s",
                "--fake", "-1", "--ttl", "8", "--fake-jitter", "--delay-range", "1-5"
            )
            PROFILE_ADAPTIVE -> listOf(
                "--proto=tls", "--split-range", "1-3+s", "--tlsrec", "1-3+s",
                "--auto=torst,redirect,ssl_err", "--disorder", "1", "--tlsrec", "1-3+s",
                "--auto=torst,redirect,ssl_err", "--fake", "-1", "--ttl-range", "7-10",
                "--fake-jitter", "--cache-ttl", "3600", "--delay-range", "1-5",
                "--timeout", "3"
            )
            PROFILE_CUSTOM -> shellSplit(profileItem.pingNgDesyncArgs.orEmpty())
                .takeIf { it.isNotEmpty() }
                ?: return null
            else -> return null
        }
        return buildList {
            add("ciadpi")
            addAll(strategy)
            addAll(listOf("--ip", "127.0.0.1", "--port", port.toString()))
        }
    }

    data class CustomOptions(
        val method: String = METHOD_SPLIT,
        val position: String = "1",
        val positionSuffix: String = "",
        val fakeTtl: String = "8",
        val oobTtl: String = "0",
        val tlsRecordPosition: String = "1",
        val timeoutSeconds: String = "3",
        val fakeSni: String = "",
        val fakeJitter: Boolean = true,
        val automaticFallback: Boolean = false,
        val cacheTtlSeconds: String = "3600",
        val delayMilliseconds: String = "1",
        val waitForSend: Boolean = true,
        val modifyHttpHeaders: Boolean = false,
        val udpFakeCount: String = "0",
        val fakeOffset: String = "0",
        val splitRange: String = "",
        val ttlRange: String = "",
        val delayRange: String = "1-5",
        val fakeData: String = "",
        val oobData: String = "",
        val dropSack: Boolean = false,
        val tcpFastOpen: Boolean = false,
        val hosts: String = "",
        val portFilter: String = "",
    )

    fun normalizeFakeSniList(input: String): String {
        val hasTrailingSeparator = input.lastOrNull()?.let { char ->
            char.isWhitespace() || char == ',' || char == ';' || char == '|'
        } == true
        val values = input
            .split(Regex("[,;|\\s]+"))
            .map(String::trim)
            .filter { it.isNotEmpty() && isValidFakeSni(it) }
            .distinct()
        if (values.isEmpty()) return ""
        return values.joinToString(", ") + if (hasTrailingSeparator) ", " else ""
    }

    fun canonicalFakeSniList(input: String): String {
        val complete = normalizeFakeSniList(input).removeSuffix(", ")
        // Keep an unfinished token visible instead of erasing the entire field
        // when focus changes immediately after the user types a dot.
        return complete.ifBlank { input.trim() }
    }

    /** Normalizes separators while preserving incomplete domains during typing. */
    fun normalizeFakeSniTyping(input: String): String {
        // Do not rewrite ordinary keystrokes (especially a just-typed comma).
        // Replacing the controlled TextField value on every keystroke moves the
        // cursor and makes the next character appear at the wrong side of the
        // previous word. Formatting is only needed after a whitespace/alternate
        // separator, and the complete canonical form is applied on focus loss.
        if (!input.any { it.isWhitespace() || it == ';' || it == '|' }) return input
        val hasTrailingSeparator = input.lastOrNull()?.let { char ->
            char.isWhitespace() || char == ',' || char == ';' || char == '|'
        } == true
        val values = input
            .replace(';', ',')
            .replace('|', ',')
            .split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (values.isEmpty()) return ""
        return values.joinToString(", ") + if (hasTrailingSeparator) ", " else ""
    }

    private fun isValidFakeSni(value: String): Boolean =
        value.length <= 253 &&
            value.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }

    fun parseCustomOptions(arguments: String): CustomOptions {
        val tokens = shellSplit(arguments)
        fun valueAfter(flag: String): String? = tokens.indexOf(flag)
            .takeIf { it >= 0 && it + 1 < tokens.size }
            ?.let { tokens[it + 1] }
            
        // این تابع تمام مقادیر یک فلگ (مثل --tls-sni) را استخراج می‌کند
        fun valuesAfter(flag: String): List<String> {
            val results = mutableListOf<String>()
            for (i in 0 until tokens.size - 1) {
                if (tokens[i] == flag) {
                    results.add(tokens[i + 1])
                }
            }
            return results
        }

        val (method, flag) = when {
            "--disorder" in tokens -> METHOD_DISORDER to "--disorder"
            "--fake" in tokens -> METHOD_FAKE_SNI to "--fake"
            "--oob" in tokens -> METHOD_OUT_OF_BAND to "--oob"
            "--disoob" in tokens -> METHOD_DISORDER_OUT_OF_BAND to "--disoob"
            else -> METHOD_SPLIT to "--split"
        }
        val rawPosition = valueAfter(flag)
        return CustomOptions(
            method = method,
            position = rawPosition?.removeSuffix("+s")?.removeSuffix("+h")?.removeSuffix("+e")
                ?: if (method == METHOD_FAKE_SNI) "-1" else "1",
            positionSuffix = when {
                rawPosition?.endsWith("+s") == true -> "+s"
                rawPosition?.endsWith("+h") == true -> "+h"
                rawPosition?.endsWith("+e") == true -> "+e"
                else -> ""
            },
            fakeTtl = valueAfter("--ttl") ?: "8",
            oobTtl = valueAfter("--oob-ttl") ?: defaultOobTtl(method),
            tlsRecordPosition = valuesAfter("--tlsrec")
                .map { it.removeSuffix("+s") }
                .distinct()
                .joinToString(",")
                .ifBlank { "1" },
            timeoutSeconds = valueAfter("--timeout") ?: "3",
            fakeSni = valuesAfter("--tls-sni").distinct().joinToString(", "),
            fakeJitter = "--fake-jitter" in tokens,
            automaticFallback = tokens.any { it.startsWith("--auto") },
            cacheTtlSeconds = valueAfter("--cache-ttl") ?: "3600",
            delayMilliseconds = valueAfter("--delay") ?: "1",
            waitForSend = "--not-wait-send" !in tokens,
            modifyHttpHeaders = "--mod-http" in tokens,
            udpFakeCount = valueAfter("--udp-fake") ?: "0",
            fakeOffset = valueAfter("--fake-offset") ?: "0",
            splitRange = valueAfter("--split-range")?.removeSuffix("+s") ?: "",
            ttlRange = valueAfter("--ttl-range") ?: "",
            delayRange = valueAfter("--delay-range") ?: "1-5",
            fakeData = valueAfter("--fake-data") ?: "",
            oobData = valueAfter("--oob-data") ?: "",
            dropSack = "--drop-sack" in tokens,
            tcpFastOpen = "--tfo" in tokens,
            hosts = valueAfter("--hosts")?.removePrefix(":") ?: "",
            portFilter = valueAfter("--pf") ?: ""
        )
    }

    fun buildCustomArguments(options: CustomOptions): String {
        fun integer(value: String, fallback: Int, range: IntRange): Int =
            value.trim().toIntOrNull()?.coerceIn(range) ?: fallback

        val method = normalizeMethod(options.method)
        val isFake = method == METHOD_FAKE_SNI
        val isOob = method == METHOD_OUT_OF_BAND || method == METHOD_DISORDER_OUT_OF_BAND
        val position = if (isFake) {
            integer(options.position, -1, -65535..65535).takeIf { it != 0 } ?: -1
        } else {
            integer(options.position, 1, 1..65535)
        }
        val positionToken = position.toString() + when (options.positionSuffix.trim()) {
            "+s", "+h", "+e" -> options.positionSuffix.trim()
            else -> ""
        }
        val flag = when (method) {
            METHOD_DISORDER -> "--disorder"
            METHOD_FAKE_SNI -> "--fake"
            METHOD_OUT_OF_BAND -> "--oob"
            METHOD_DISORDER_OUT_OF_BAND -> "--disoob"
            else -> "--split"
        }
        val normalizedSplitRange = boundedRange(options.splitRange, 1, 65535)
        val primaryPosition = if (method == METHOD_SPLIT && normalizedSplitRange != null) {
            "--split-range" to "$normalizedSplitRange+s"
        } else {
            flag to if (isFake) positionToken else "$position+s"
        }
        val result = mutableListOf("--proto=tls", primaryPosition.first, primaryPosition.second)
        if (isFake) {
            result += listOf("--ttl", integer(options.fakeTtl, 8, 1..255).toString())
            val snis = normalizeFakeSniTyping(options.fakeSni)
                .split(',', ';', '|')
                .map(String::trim)
                .filter { it.isNotEmpty() && isValidFakeSni(it) }
                .distinct()
            if (snis.isNotEmpty()) {
                snis.forEach { sni ->
                    result += listOf("--tls-sni", sni)
                }
            }
            if (options.fakeJitter) result += "--fake-jitter"
            val fakeOffset = integer(options.fakeOffset, 0, 0..65535)
            if (fakeOffset > 0) result += listOf("--fake-offset", fakeOffset.toString())
        }
        if (isOob) {
            val oobTtl = normalizeOobTtl(method, options.oobTtl).toInt()
            val defaultOobTtl = defaultOobTtl(method).toInt()
            if (oobTtl != defaultOobTtl) {
                result += listOf("--oob-ttl", oobTtl.toString())
            }
        }
        val tlsRecords = if (options.tlsRecordPosition.isBlank()) {
            emptyList()
        } else {
            options.tlsRecordPosition
                .split(',', ';', ' ', '|')
                .mapNotNull { boundedRange(it.trim(), 1, 65535) }
                .distinct()
        }
        val normalizedTtlRange = boundedRange(options.ttlRange, 1, 255)
        if (isFake && normalizedTtlRange != null) {
            result += listOf("--ttl-range", normalizedTtlRange)
        }
        if (options.modifyHttpHeaders) result += listOf("--mod-http", "h,d,r")
        val udpFakeCount = integer(options.udpFakeCount, 0, 0..8)
        if (udpFakeCount > 0) result += listOf("--udp-fake", udpFakeCount.toString())
        options.fakeData.trim().takeIf { it.isNotEmpty() }?.let {
            result += listOf("--fake-data", shellQuote(it))
        }
        options.oobData.trim().takeIf { it.isNotEmpty() }?.let {
            result += listOf("--oob-data", shellQuote(it))
        }
        if (options.dropSack) result += "--drop-sack"
        if (options.tcpFastOpen) result += "--tfo"
        options.hosts.trim().takeIf { it.isNotEmpty() }?.let {
            result += listOf("--hosts", shellQuote(":$it"))
        }
        options.portFilter.trim().takeIf { it.isNotEmpty() }?.let {
            result += listOf("--pf", shellQuote(it))
        }
        if (options.automaticFallback) {
            result += listOf("--auto=torst,redirect,ssl_err")
            result += listOf("--split", "1+s")
            tlsRecords.forEach { record -> result += listOf("--tlsrec", "$record+s") }
        } else {
            tlsRecords.forEach { record -> result += listOf("--tlsrec", "$record+s") }
        }
        result += listOf("--timeout", integer(options.timeoutSeconds, 3, 1..3600).toString())
        result += listOf("--cache-ttl", integer(options.cacheTtlSeconds, 3600, 60..604800).toString())
        val normalizedDelayRange = boundedRange(options.delayRange, 0, 999)
        if (normalizedDelayRange != null) {
            result += listOf("--delay-range", normalizedDelayRange)
        } else {
            result += listOf("--delay", integer(options.delayMilliseconds, 1, 0..999).toString())
        }
        if (!options.waitForSend) result += "--not-wait-send"
        return result.joinToString(" ")
    }

    private fun boundedRange(value: String, min: Int, max: Int): String? {
        val input = value.trim()
        if (input.isEmpty()) return null
        val parts = input.split('-', limit = 2)
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        val last = parts.getOrNull(1)?.toIntOrNull() ?: first
        if (first !in min..max || last !in min..max || last < first) return null
        return if (first == last) first.toString() else "$first-$last"
    }

    private fun shellQuote(value: String): String =
        if (value.any { it.isWhitespace() || it == '\'' || it == '"' }) {
            "'" + value.replace("'", "'\\''") + "'"
        } else value

    fun defaultOobTtl(method: String): String =
        if (normalizeMethod(method) == METHOD_DISORDER_OUT_OF_BAND) "1" else "0"

    fun normalizeOobTtl(method: String, value: String): String {
        val fallback = defaultOobTtl(method).toInt()
        return (value.trim().toIntOrNull() ?: fallback).coerceIn(0..255).toString()
    }

    private fun normalizeMethod(value: String): String = when (value) {
        "Fake", METHOD_FAKE_SNI -> METHOD_FAKE_SNI
        "OOB", METHOD_OUT_OF_BAND -> METHOD_OUT_OF_BAND
        "Disorder + OOB", METHOD_DISORDER_OUT_OF_BAND -> METHOD_DISORDER_OUT_OF_BAND
        METHOD_DISORDER -> METHOD_DISORDER
        else -> METHOD_SPLIT
    }

    fun attachNativeProxy(
        config: V2rayConfig,
        profileItem: ProfileItem,
        port: Int
    ): Boolean {
        if (!isNativeDesyncEnabled(profileItem) || port !in 1..65535) return false
        val primary = config.outbounds.firstOrNull { it.tag == "proxy" } ?: return false
        val stream = primary.streamSettings ?: V2rayConfig.OutboundBean.StreamSettingsBean().also {
            primary.streamSettings = it
        }
        val sockopt = stream.sockopt ?: V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean().also {
            stream.sockopt = it
        }
        // A reload can reach this point after the native Desync chain was
        // already attached. Keep the existing chain valid instead of treating
        // it as a conflict with Final Mask.
        if (sockopt.dialerProxy == PingNgDesyncManager.OUTBOUND_TAG) {
            config.outbounds.removeAll { it.tag == PingNgDesyncManager.OUTBOUND_TAG }
            config.outbounds.add(
                V2rayConfig.OutboundBean(
                    tag = PingNgDesyncManager.OUTBOUND_TAG,
                    protocol = "socks",
                    settings = V2rayConfig.OutboundBean.OutSettingsBean(
                        address = "127.0.0.1",
                        port = port,
                    ),
                )
            )
            return true
        }
        if (!sockopt.dialerProxy.isNullOrBlank()) return false

        sockopt.dialerProxy = PingNgDesyncManager.OUTBOUND_TAG
        config.outbounds.removeAll { it.tag == PingNgDesyncManager.OUTBOUND_TAG }
        config.outbounds.add(
            V2rayConfig.OutboundBean(
                tag = PingNgDesyncManager.OUTBOUND_TAG,
                protocol = "socks",
                settings = V2rayConfig.OutboundBean.OutSettingsBean(
                    address = "127.0.0.1",
                    port = port
                )
            )
        )
        return true
    }

    private fun normalizeProfile(value: String?): String = when (value?.trim()) {
        "Aggressive" -> PROFILE_SEVERE
        PROFILE_OFF -> PROFILE_OFF
        PROFILE_LIGHT -> PROFILE_LIGHT
        PROFILE_BALANCED -> PROFILE_BALANCED
        PROFILE_SEVERE -> PROFILE_SEVERE
        PROFILE_ADAPTIVE -> PROFILE_ADAPTIVE
        PROFILE_CUSTOM -> PROFILE_CUSTOM
        else -> PROFILE_OFF
    }

    internal fun shellSplit(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }

        input.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> flush()
                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        flush()
        return result
    }
}
