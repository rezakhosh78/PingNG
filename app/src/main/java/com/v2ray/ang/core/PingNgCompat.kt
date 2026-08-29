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
        EConfigType.TROJAN -> true
        else -> false
    }

    fun isNativeDesyncEnabled(profileItem: ProfileItem): Boolean =
        supportsNativeDesync(profileItem) &&
            normalizeProfile(profileItem.pingNgProfile) != PROFILE_OFF

    fun getPresetArguments(profile: String): String {
        return when (normalizeProfile(profile)) {
            PROFILE_LIGHT -> "--proto=tls --split 1+s --tlsrec 1+s"
            PROFILE_BALANCED -> "--proto=tls --disorder 1 --tlsrec 1+s"
            PROFILE_SEVERE -> "--proto=tls --split 1+s --disorder 3+s --fake -1 --ttl 8"
            PROFILE_ADAPTIVE -> "--proto=tls --disorder 1 --auto=torst,ssl_err --timeout 3 --tlsrec 3+s"
            else -> ""
        }
    }

    fun buildCommandLine(profileItem: ProfileItem, port: Int): List<String>? {
        if (!isNativeDesyncEnabled(profileItem) || port !in 1..65535) return null
        val strategy = when (normalizeProfile(profileItem.pingNgProfile)) {
            PROFILE_LIGHT -> listOf(
                "--proto=tls", "--split", "1+s", "--tlsrec", "1+s"
            )
            PROFILE_BALANCED -> listOf(
                "--proto=tls", "--disorder", "1", "--tlsrec", "1+s"
            )
            PROFILE_SEVERE -> listOf(
                "--proto=tls", "--split", "1+s", "--disorder", "3+s",
                "--fake", "-1", "--ttl", "8"
            )
            PROFILE_ADAPTIVE -> listOf(
                "--proto=tls", "--disorder", "1", "--auto=torst,ssl_err",
                "--timeout", "3", "--tlsrec", "3+s"
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
        val fakeTtl: String = "8",
        val tlsRecordPosition: String = "1",
        val timeoutSeconds: String = "3",
        val fakeSni: String = "",
    )

    fun normalizeFakeSniList(input: String): String {
        val hasTrailingSeparator = input.lastOrNull()?.let { char ->
            char.isWhitespace() || char == ',' || char == ';' || char == '|'
        } == true
        val values = input
            .split(Regex("[,;|\\s]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (values.isEmpty()) return ""
        return values.joinToString(", ") + if (hasTrailingSeparator) ", " else ""
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
        return CustomOptions(
            method = method,
            position = valueAfter(flag)?.removeSuffix("+s") ?: if (method == METHOD_FAKE_SNI) "-1" else "1",
            fakeTtl = valueAfter("--ttl") ?: "8",
            tlsRecordPosition = valueAfter("--tlsrec")?.removeSuffix("+s") ?: "1",
            timeoutSeconds = valueAfter("--timeout") ?: "3",
            fakeSni = valuesAfter("--tls-sni").distinct().joinToString(", ")
        )
    }

    fun buildCustomArguments(options: CustomOptions): String {
        fun integer(value: String, fallback: Int, range: IntRange): Int =
            value.trim().toIntOrNull()?.coerceIn(range) ?: fallback

        val method = normalizeMethod(options.method)
        val isFake = method == METHOD_FAKE_SNI
        val position = if (isFake) {
            integer(options.position, -1, -65535..65535).takeIf { it != 0 } ?: -1
        } else {
            integer(options.position, 1, 1..65535)
        }
        val flag = when (method) {
            METHOD_DISORDER -> "--disorder"
            METHOD_FAKE_SNI -> "--fake"
            METHOD_OUT_OF_BAND -> "--oob"
            METHOD_DISORDER_OUT_OF_BAND -> "--disoob"
            else -> "--split"
        }
        val result = mutableListOf("--proto=tls", flag, if (isFake) "$position" else "$position+s")
        if (isFake) {
            result += listOf("--ttl", integer(options.fakeTtl, 8, 1..255).toString())
            val snis = normalizeFakeSniList(options.fakeSni)
                .removeSuffix(", ")
                .split(", ")
                .filter(String::isNotEmpty)
            if (snis.isNotEmpty()) {
                snis.forEach { sni ->
                    result += listOf("--tls-sni", sni)
                }
            }
        }
        val tlsRecord = integer(options.tlsRecordPosition, 1, 1..65535)
        result += listOf("--tlsrec", "$tlsRecord+s")
        result += listOf("--timeout", integer(options.timeoutSeconds, 3, 1..3600).toString())
        return result.joinToString(" ")
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
