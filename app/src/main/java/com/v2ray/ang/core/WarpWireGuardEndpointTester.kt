package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager

/** Selects a WireGuard WARP endpoint with the shared protocol scanner. */
object WarpWireGuardEndpointTester {
    suspend fun testAndSelect(
        context: Context,
        guid: String,
        onProgress: suspend (String) -> Unit = {},
    ): Boolean {
        val original = MmkvManager.decodeServerConfig(guid) ?: return false
        if (!WarpWireGuardConfig.isProfile(original)) return false
        val account = EndpointScannerCli.accountFromProfile(original) ?: return false
        val candidates = EndpointScannerCli.scanWireGuardEndpoints(
            context,
            account,
            scanGuid = guid,
            progressLabel = "WARP WireGuard",
        ) { progress ->
            onProgress("WARP WireGuard: $progress")
        }
        val selected = candidates.first()
        val endpoint = parseEndpoint(selected.endpoint) ?: return false
        persist(guid, original.copy(
            awgEndpointCandidates = EndpointScannerCli.encodeCandidates(candidates),
        ), endpoint)
        AwgEndpointScanState.complete(guid, "Endpoint selected: ${selected.endpoint}", selected.endpoint)
        return true
    }

    private fun persist(guid: String, original: ProfileItem, endpoint: WarpEndpointTester.Endpoint) {
        val host = endpoint.host
        MmkvManager.encodeServerConfig(guid, original.copy(
            server = host,
            serverPort = endpoint.port.toString(),
            warpWireGuardSelectedEndpoint = "${displayHost(host)}:${endpoint.port}",
            description = WarpWireGuardConfig.DESCRIPTION,
            remarks = original.remarks.takeIf { it.isNotBlank() && !it.equals("WireGuard", true) }
                ?: "WARP WireGuard",
        ))
    }

    private fun parseEndpoint(raw: String): WarpEndpointTester.Endpoint? {
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
        return WarpEndpointTester.Endpoint(parts.first, port)
    }

    private fun displayHost(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
}
