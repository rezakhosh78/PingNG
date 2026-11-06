package com.v2ray.ang.core

import android.util.Base64
import com.google.gson.JsonParser
import com.v2ray.ang.util.JsonUtil
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** JSON import/export and compact-link codec for native Desync settings. */
object DesyncProfileJson {
    private const val CURRENT_VERSION = 1
    private const val MAX_JSON_BYTES = 16 * 1024
    private const val MAX_LINK_VALUE_LENGTH = 4096

    data class Settings(
        val profile: String,
        val arguments: String,
    )

    private data class Document(
        val version: Int = CURRENT_VERSION,
        val profile: String = PingNgCompat.PROFILE_OFF,
        val arguments: String? = null,
        val options: PingNgCompat.CustomOptions? = null,
    )

    /** Returns editable, human-readable JSON for the current profile. */
    fun toJson(profile: String?, arguments: String?): String {
        val normalizedProfile = normalizeProfile(profile) ?: PingNgCompat.PROFILE_OFF
        val args = arguments.orEmpty().trim()
        val document = when {
            normalizedProfile == PingNgCompat.PROFILE_OFF -> Document(profile = normalizedProfile)
            normalizedProfile == PingNgCompat.PROFILE_CUSTOM -> Document(
                profile = normalizedProfile,
                arguments = args,
                options = PingNgCompat.parseCustomOptions(args),
            )
            else -> Document(
                profile = normalizedProfile,
                arguments = args.ifBlank { PingNgCompat.getPresetArguments(normalizedProfile) },
            )
        }
        return JsonUtil.toJsonPretty(document) ?: JsonUtil.toJson(document)
    }

    /** Parses the editable JSON and returns the profile/arguments consumed by the engine. */
    fun fromJson(
        json: String,
        currentProfile: String? = null,
        currentArguments: String? = null,
    ): Settings? {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val document = runCatching { JsonUtil.fromJson(json, Document::class.java) }.getOrNull() ?: return null
        if (document.version != CURRENT_VERSION) return null

        val optionsElement = root.get("options") ?: root.get("fields") ?: root.takeIf { candidate ->
            listOf(
                "method", "position", "positionSuffix", "fakeTtl", "oobTtl", "tlsRecordPosition",
                "timeoutSeconds", "fakeSni", "fakeJitter", "automaticFallback",
                "cacheTtlSeconds", "delayMilliseconds", "waitForSend", "modifyHttpHeaders",
                "udpFakeCount", "fakeOffset", "splitRange", "ttlRange", "delayRange",
                "fakeData", "oobData", "dropSack", "tcpFastOpen", "hosts", "portFilter",
            ).any { candidate.has(it) }
        }
        val explicitProfile = root.get("profile")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("desyncProfile")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("pingNgProfile")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("profileName")?.takeIf { it.isJsonPrimitive }?.asString
        val defaultProfile = if (explicitProfile == null && optionsElement != null) {
            PingNgCompat.PROFILE_CUSTOM
        } else {
            document.profile
        }
        val requestedProfile = normalizeProfile(
            explicitProfile ?: defaultProfile,
        ) ?: return null
        if (requestedProfile == PingNgCompat.PROFILE_OFF) {
            return Settings(PingNgCompat.PROFILE_OFF, "")
        }

        val currentNormalizedProfile = normalizeProfile(currentProfile)
        val currentRawArguments = when {
            currentNormalizedProfile == null -> null
            currentNormalizedProfile == PingNgCompat.PROFILE_OFF -> ""
            currentArguments.isNullOrBlank() && currentNormalizedProfile != PingNgCompat.PROFILE_CUSTOM ->
                PingNgCompat.getPresetArguments(currentNormalizedProfile)
            else -> currentArguments.orEmpty().trim()
        }
        if (
            currentNormalizedProfile != null &&
            requestedProfile != currentNormalizedProfile &&
            requestedProfile != PingNgCompat.PROFILE_CUSTOM
        ) {
            return Settings(requestedProfile, PingNgCompat.getPresetArguments(requestedProfile))
        }

        if (optionsElement?.isJsonObject == true) {
            val options = runCatching {
                JsonUtil.fromJson(optionsElement.toString(), PingNgCompat.CustomOptions::class.java)
            }.getOrNull() ?: return null
            val rawArguments = root.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("args")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("customArgs")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("pingNgDesyncArgs")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("pingNgArgs")?.takeIf { it.isJsonPrimitive }?.asString
                ?: document.arguments.orEmpty()
            if (currentRawArguments != null) {
                val argsChanged = rawArguments.isNotBlank() && rawArguments.trim() != currentRawArguments
                val optionsChanged = options != PingNgCompat.parseCustomOptions(currentRawArguments)
                if (argsChanged && !optionsChanged) {
                    if (
                        requestedProfile != PingNgCompat.PROFILE_CUSTOM &&
                        rawArguments.trim() == PingNgCompat.getPresetArguments(requestedProfile)
                    ) {
                        return Settings(requestedProfile, rawArguments.trim())
                    }
                    return Settings(PingNgCompat.PROFILE_CUSTOM, rawArguments.trim())
                }
                if (!argsChanged && !optionsChanged) {
                    if (requestedProfile == PingNgCompat.PROFILE_CUSTOM) {
                        val retainedArguments = rawArguments.ifBlank {
                            PingNgCompat.buildCustomArguments(options)
                        }
                        return Settings(PingNgCompat.PROFILE_CUSTOM, retainedArguments.trim())
                    }
                    return Settings(
                        requestedProfile,
                        PingNgCompat.getPresetArguments(requestedProfile),
                    )
                }
                if (
                    argsChanged && optionsChanged && rawArguments.isNotBlank() &&
                    options == PingNgCompat.parseCustomOptions(rawArguments)
                ) {
                    return Settings(PingNgCompat.PROFILE_CUSTOM, rawArguments.trim())
                }
                if (
                    requestedProfile != PingNgCompat.PROFILE_CUSTOM &&
                    options == PingNgCompat.parseCustomOptions(
                        PingNgCompat.getPresetArguments(requestedProfile),
                    )
                ) {
                    return Settings(
                        requestedProfile,
                        PingNgCompat.getPresetArguments(requestedProfile),
                    )
                }
                return Settings(PingNgCompat.PROFILE_CUSTOM, PingNgCompat.buildCustomArguments(options))
            }
            if (
                rawArguments.isNotBlank() &&
                options == PingNgCompat.parseCustomOptions(rawArguments)
            ) {
                if (
                    requestedProfile != PingNgCompat.PROFILE_CUSTOM &&
                    rawArguments.trim() in BUILT_IN_PRESET_ARGUMENTS
                ) {
                    return Settings(requestedProfile, PingNgCompat.getPresetArguments(requestedProfile))
                }
                return Settings(PingNgCompat.PROFILE_CUSTOM, rawArguments.trim())
            }
            val customArguments = PingNgCompat.buildCustomArguments(options)
            if (requestedProfile != PingNgCompat.PROFILE_CUSTOM) {
                val presetOptions = PingNgCompat.parseCustomOptions(
                    PingNgCompat.getPresetArguments(requestedProfile),
                )
                if (options == presetOptions) {
                    return Settings(
                        requestedProfile,
                        PingNgCompat.getPresetArguments(requestedProfile),
                    )
                }
            }
            return Settings(PingNgCompat.PROFILE_CUSTOM, customArguments)
        }

        val rawArguments = root.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("args")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("customArgs")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("pingNgDesyncArgs")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("pingNgArgs")?.takeIf { it.isJsonPrimitive }?.asString
            ?: document.arguments.orEmpty()
        if (currentRawArguments != null) {
            if (rawArguments.isBlank() || rawArguments.trim() == currentRawArguments) {
                if (requestedProfile == PingNgCompat.PROFILE_CUSTOM) {
                    return Settings(
                        PingNgCompat.PROFILE_CUSTOM,
                        currentRawArguments.ifBlank {
                            PingNgCompat.buildCustomArguments(PingNgCompat.CustomOptions())
                        },
                    )
                }
                return Settings(
                    requestedProfile,
                    PingNgCompat.getPresetArguments(requestedProfile),
                )
            }
            if (
                requestedProfile != PingNgCompat.PROFILE_CUSTOM &&
                rawArguments.trim() == PingNgCompat.getPresetArguments(requestedProfile)
            ) {
                return Settings(requestedProfile, rawArguments.trim())
            }
            return Settings(PingNgCompat.PROFILE_CUSTOM, rawArguments.trim())
        }
        if (rawArguments.isNotBlank()) {
            if (
                requestedProfile != PingNgCompat.PROFILE_CUSTOM &&
                rawArguments.trim() in BUILT_IN_PRESET_ARGUMENTS
            ) {
                return Settings(requestedProfile, PingNgCompat.getPresetArguments(requestedProfile))
            }
            return Settings(PingNgCompat.PROFILE_CUSTOM, rawArguments.trim())
        }

        if (requestedProfile == PingNgCompat.PROFILE_CUSTOM) return null
        return Settings(requestedProfile, PingNgCompat.getPresetArguments(requestedProfile))
    }

    /** Encodes only the enabled profile as a compact, URL-safe value for share links. */
    fun toCompactLinkValue(profile: String?, arguments: String?): String? {
        val normalizedProfile = normalizeProfile(profile) ?: return null
        if (normalizedProfile == PingNgCompat.PROFILE_OFF) return null
        val args = arguments.orEmpty()
        if (normalizedProfile == PingNgCompat.PROFILE_CUSTOM && args.isBlank()) return null
        val compactJson = JsonUtil.toJson(
            Document(
                profile = normalizedProfile,
                arguments = args.takeIf { normalizedProfile == PingNgCompat.PROFILE_CUSTOM },
            ),
        )
        val source = compactJson.toByteArray(Charsets.UTF_8)
        if (source.size > MAX_JSON_BYTES) return null
        val compressor = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = try {
            ByteArrayOutputStream().use { output ->
                DeflaterOutputStream(output, compressor).use { deflater ->
                    deflater.write(source)
                }
                output.toByteArray()
            }
        } finally {
            compressor.end()
        }
        return Base64.encodeToString(
            compressed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        ).takeIf { it.length <= MAX_LINK_VALUE_LENGTH }
    }

    /** Decodes the `pngd` value, while bounding input and decompressed sizes. */
    fun fromCompactLinkValue(value: String?): Settings? {
        if (value.isNullOrBlank() || value.length > MAX_LINK_VALUE_LENGTH) return null
        return runCatching {
            val compressed = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val json = InflaterInputStream(compressed.inputStream()).use { inflater ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = inflater.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_JSON_BYTES) return null
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
            fromJson(json)
        }.getOrNull()
    }

    private fun normalizeProfile(value: String?): String? = when (value?.trim()) {
        "Off" -> PingNgCompat.PROFILE_OFF
        "Light" -> PingNgCompat.PROFILE_LIGHT
        "Balanced" -> PingNgCompat.PROFILE_BALANCED
        "Severe", "Aggressive" -> PingNgCompat.PROFILE_SEVERE
        "Adaptive" -> PingNgCompat.PROFILE_ADAPTIVE
        "Custom" -> PingNgCompat.PROFILE_CUSTOM
        else -> null
    }

    private val BUILT_IN_PRESET_ARGUMENTS = listOf(
        PingNgCompat.PROFILE_LIGHT,
        PingNgCompat.PROFILE_BALANCED,
        PingNgCompat.PROFILE_SEVERE,
        PingNgCompat.PROFILE_ADAPTIVE,
    ).map(PingNgCompat::getPresetArguments)
}
