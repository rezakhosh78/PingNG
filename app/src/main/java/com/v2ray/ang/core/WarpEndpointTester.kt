package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

/** Selects one WireGuard endpoint for each hop in a WARP Plus profile. */
object WarpEndpointTester {
    data class Endpoint(val host: String, val port: Int)

    suspend fun testAndSelect(
        context: Context,
        parentGuid: String,
        onProgress: suspend (String) -> Unit = {},
    ): Boolean {
        val parent = MmkvManager.decodeServerConfig(parentGuid) ?: return false
        if (!WarpPlusConfig.isDescription(parent.description)) return false
        val childRemarks = parent.proxyChainProfiles.orEmpty().split(",")
            .map(String::trim).filter(String::isNotBlank)
        if (childRemarks.size < 2) return false
        val children = childRemarks.mapNotNull { remark ->
            val profile = SettingsManager.getServerViaRemarks(remark) ?: return@mapNotNull null
            val guid = MmkvManager.decodeAllServerList().firstOrNull { id ->
                MmkvManager.decodeServerConfig(id)?.remarks == profile.remarks
            } ?: return@mapNotNull null
            guid to profile
        }
        if (children.size < 2) return false

        val legacyOuterFirst = parent.description == WarpPlusConfig.LEGACY_DESCRIPTION
        val outerIndex = if (legacyOuterFirst) 0 else 1
        val innerIndex = if (legacyOuterFirst) 1 else 0
        val outerGuid = children.getOrNull(outerIndex)?.first ?: return false
        val innerGuid = children.getOrNull(innerIndex)?.first ?: return false
        val outer = children[outerIndex].second
        val inner = children[innerIndex].second

        val innerAccount = EndpointScannerCli.accountFromProfile(inner) ?: return false
        onProgress("Scanning endpoints…")
        val innerCandidates = EndpointScannerCli.scanWireGuardEndpoints(
            context,
            innerAccount,
            scanGuid = parentGuid,
            progressLabel = "Inner WARP",
        ) { progress ->
            onProgress("WARP Plus inner: $progress")
        }
        val innerResult = innerCandidates.first()
        val selectedInner = parseEndpoint(innerResult.endpoint) ?: return false

        val selectedOuter = selectedInner
        val finalMask = selectedFinalMask(parent, outer)

        MmkvManager.encodeServerConfig(
            innerGuid,
            inner.copy(server = selectedInner.host, serverPort = selectedInner.port.toString()),
        )
        MmkvManager.encodeServerConfig(
            outerGuid,
            outer.copy(
                server = selectedOuter.host,
                serverPort = selectedOuter.port.toString(),
                finalMask = finalMask,
            ),
        )
        MmkvManager.encodeServerConfig(
            parentGuid,
            parent.copy(
                warpEndpointTestMode = WarpPlusConfig.ENDPOINT_MODE_AUTO,
                awgEndpointCandidates = EndpointScannerCli.encodeCandidates(innerCandidates),
                warpSelectedEndpoint = "inner ${formatEndpoint(selectedInner)}  •  outer ${formatEndpoint(selectedOuter)}",
            ),
        )
        AwgEndpointScanState.complete(parentGuid, "Endpoint selected: ${innerResult.endpoint}", innerResult.endpoint)
        return true
    }

    private fun selectedFinalMask(parent: ProfileItem, outer: ProfileItem): String? {
        if (parent.warpFinalMaskEnabled == false) return null
        val selected = parent.warpFastFinalMask?.takeIf { it.isNotBlank() }
            ?: parent.warpAllFinalMask
        return selected?.takeIf { it.isNotBlank() }
            ?: outer.finalMask?.takeIf { it.isNotBlank() }
            ?: WarpPlusConfig.DEFAULT_FINAL_MASK
    }

    private fun parseEndpoint(raw: String): Endpoint? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val parts = if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close <= 0) return null
            text.substring(1, close) to text.substring(close + 1).removePrefix(":")
        } else {
            val separator = text.lastIndexOf(':')
            if (separator <= 0) return null
            text.substring(0, separator) to text.substring(separator + 1)
        }
        val port = parts.second.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Endpoint(parts.first, port)
    }

    private fun formatEndpoint(endpoint: Endpoint): String {
        val host = endpoint.host.trim()
        return "${if (host.contains(':') && !host.startsWith("[")) "[$host]" else host}:${endpoint.port}"
    }
}
