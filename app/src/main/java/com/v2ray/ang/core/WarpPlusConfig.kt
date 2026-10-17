package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem

/** Shared defaults and marker used by the WARP Plus (WARP-in-WARP) profile. */
object WarpPlusConfig {
    const val DESCRIPTION = "WARP Plus"
    const val LEGACY_DESCRIPTION = "WARP outer → WARP inner"

    const val ENDPOINT_MODE_AUTO = "Automatic"
    const val ENDPOINT_MODE_CUSTOM = "Custom"

    /** Keep profiles written by older builds readable after the UI rename. */
    fun normalizeEndpointTestMode(value: String?): String =
        if (value?.trim() == ENDPOINT_MODE_CUSTOM) ENDPOINT_MODE_CUSTOM else ENDPOINT_MODE_AUTO

    fun isDescription(value: String?): Boolean =
        value == DESCRIPTION || value == LEGACY_DESCRIPTION

    const val DEFAULT_KEEP_ALIVE = 5
    const val DEFAULT_FINAL_MASK = """{"udp":[{"type":"noise","settings":{"reset":"30-60","noise":[{"rand":"50-100","randRange":"16-255","delay":"1-3"},{"rand":"50-100","randRange":"16-255","delay":"1-3"},{"rand":"50-100","randRange":"16-255","delay":"1-3"}]}}]}"""
}

/** Resolves the parent-level WARP-in-WARP FinalMask onto its outer WireGuard hop. */
object WarpPlusFinalMaskPolicy {
    fun effectiveMask(parent: ProfileItem, outer: ProfileItem?): String? {
        if (!WarpPlusConfig.isDescription(parent.description) || parent.warpFinalMaskEnabled == false) {
            return null
        }

        val outerMask = outer?.finalMask?.takeIf(String::isNotBlank)
        val fastMask = parent.warpFastFinalMask?.takeIf(String::isNotBlank)
        val allMask = parent.warpAllFinalMask?.takeIf(String::isNotBlank)
        val automatic = WarpPlusConfig.normalizeEndpointTestMode(parent.warpEndpointTestMode) !=
            WarpPlusConfig.ENDPOINT_MODE_CUSTOM

        val preferredMasks = if (automatic) {
            listOf(fastMask, allMask, outerMask)
        } else {
            listOf(outerMask, fastMask, allMask)
        }
        return preferredMasks.firstOrNull(::isValidMaskObject) ?: WarpPlusConfig.DEFAULT_FINAL_MASK
    }

    /** Applies the effective parent setting after child profile conversion. */
    fun applyToOuterOutbound(
        parent: ProfileItem,
        outer: ProfileItem,
        outbound: V2rayConfig.OutboundBean,
    ): Boolean {
        if (!WarpPlusConfig.isDescription(parent.description)) return false
        val rawMask = effectiveMask(parent, outer)
        if (rawMask == null) {
            outbound.streamSettings?.let {
                it.finalmask = null
                it.network = null
            }
            return false
        }

        val mask = runCatching { JsonParser.parseString(rawMask) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?: return false
        val streamSettings = outbound.streamSettings
            ?: V2rayConfig.OutboundBean.StreamSettingsBean().also { outbound.streamSettings = it }
        streamSettings.finalmask = mask
        // WireGuard is already UDP-based; leaving a default TCP network here
        // makes the custom outer-hop transport settings ineffective.
        streamSettings.network = null
        return true
    }

    fun shouldShowBadge(profile: ProfileItem): Boolean = when {
        WarpPlusConfig.isDescription(profile.description) -> profile.warpFinalMaskEnabled != false
        else -> !profile.finalMask.isNullOrBlank()
    }

    private fun isValidMaskObject(raw: String?): Boolean = raw?.let {
        runCatching { JsonParser.parseString(it).isJsonObject }.getOrDefault(false)
    } == true
}
