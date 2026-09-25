package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Single-hop version of the WARP Plus tester. Only the Outer WARP is probed. */
object WarpWireGuardEndpointTester {
    private const val FIXED_TIMEOUT_MS = 4_000L
    private const val PROBE_TIMEOUT_MS = 3_000L
    private const val SEARCH_DEADLINE_MS = 60_000L
    private const val MAX_DISCOVERY_HITS = 128
    private const val PROBE_URL = "http://cp.cloudflare.com/generate_204"

    suspend fun testAndSelect(
        context: Context,
        guid: String,
        onProgress: suspend (String) -> Unit = {},
    ): Boolean {
        val original = MmkvManager.decodeServerConfig(guid) ?: return false
        if (!WarpWireGuardConfig.isProfile(original)) return false
        val result = CoreConfigManager.getV2rayConfig(context, guid)
        if (!result.status || result.content.isBlank()) return false
        CoreNativeManager.initCoreEnv(context)
        val identity = WarpScoutEndpointScanner.Identity(
            privateKey = original.secretKey.orEmpty(),
            peerPublicKey = original.publicKey.orEmpty(),
        )
        if (identity.privateKey.isBlank() || identity.peerPublicKey.isBlank()) return false

        // A persisted successful endpoint is the reconnect probe. A freshly
        // generated account has no selected endpoint yet, so it goes directly
        // to the fixed WARP Plus first candidate below.
        val previous = endpoint(original.warpWireGuardSelectedEndpoint)
        if (previous != null) {
            onProgress("WARP WireGuard reconnect 0/1")
            val started = System.nanoTime()
            val delay = probe(result.content, previous, original.finalMask, FIXED_TIMEOUT_MS)
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            onProgress("WARP WireGuard reconnect 1/1")
            if (delay >= 0L && elapsed <= FIXED_TIMEOUT_MS) {
                persist(guid, original, previous)
                return true
            }
        }

        val fixed = WarpWireGuardEndpointTester.endpoint("${WarpWireGuardConfig.DEFAULT_ENDPOINT}:${WarpWireGuardConfig.DEFAULT_PORT}")!!
        onProgress("WARP WireGuard fixed endpoint ${fixed.host}:${fixed.port}")
        val fixedStarted = System.nanoTime()
        val fixedDelay = probe(result.content, fixed, original.finalMask, FIXED_TIMEOUT_MS)
        val fixedElapsed = (System.nanoTime() - fixedStarted) / 1_000_000L
        if (fixedDelay >= 0L && fixedElapsed <= FIXED_TIMEOUT_MS) {
            persist(guid, original, fixed)
            return true
        }

        val mode = WarpWireGuardConfig.normalizeMode(original.warpEndpointTestMode)
        val rawPool = when (mode) {
            WarpWireGuardConfig.ENDPOINT_MODE_FAST -> WarpPlusConfig.FAST_ENDPOINTS
            else -> WarpPlusConfig.ALL_ENDPOINTS
        }
        val pool = parse(rawPool).filterNot { it == fixed || it == previous }.distinct()
        if (pool.isEmpty()) return false
        onProgress("WARP WireGuard discovery: 0/${pool.size}")
        val hits = WarpScoutEndpointScanner.scan(
            endpoints = pool,
            identity = identity,
            ratePerSecond = if (mode == WarpWireGuardConfig.ENDPOINT_MODE_FAST) 1_500L else 3_500L,
            timeoutMs = 800,
            maxHits = MAX_DISCOVERY_HITS,
            stopAfterHits = if (mode == WarpWireGuardConfig.ENDPOINT_MODE_FAST) null else 16,
            acceptCookieReplies = true,
            onProgress = { tested, total -> onProgress("WARP WireGuard discovery: $tested/$total") },
        ).map { it.endpoint }.distinct()
        currentCoroutineContext().ensureActive()
        if (hits.isEmpty()) return false

        onProgress("WARP WireGuard Verify 0/${hits.size}")
        val selected = WarpSearchCore.search(
            hits.map { WarpSearchCore.Candidate(it, it) },
            WarpSearchCore.Options(
                timeoutMs = PROBE_TIMEOUT_MS,
                deadlineMs = maxOf(SEARCH_DEADLINE_MS, hits.size.toLong() * (PROBE_TIMEOUT_MS + 500L)),
                workers = 3,
                chooseFastest = false,
            ),
            probe = { candidate, timeout -> probe(result.content, candidate.inner, original.finalMask, timeout) },
            onCandidateCompleted = { tested, total -> onProgress("WARP WireGuard Verify $tested/$total") },
        )
        currentCoroutineContext().ensureActive()
        val selectedEndpoint = selected?.candidate?.inner ?: return false
        persist(guid, original, selectedEndpoint)
        return true
    }

    private suspend fun probe(raw: String, endpoint: WarpEndpointTester.Endpoint, finalMask: String?, timeoutMs: Long): Long {
        val config = configForEndpoint(raw, endpoint, finalMask) ?: return -1L
        val started = System.nanoTime()
        var delay = -1L
        while ((System.nanoTime() - started) / 1_000_000L < timeoutMs) {
            currentCoroutineContext().ensureActive()
            delay = runCatching { CoreNativeManager.measureOutboundDelay(config, PROBE_URL) }.getOrDefault(-1L)
            if (delay >= 0L) return delay
            break
        }
        return delay
    }

    private fun configForEndpoint(raw: String, endpoint: WarpEndpointTester.Endpoint, finalMask: String?): String? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        val wireguard = root.getAsJsonArray("outbounds")?.firstOrNull { item ->
            item.isJsonObject && item.asJsonObject.get("protocol")?.asString == "wireguard"
        }?.asJsonObject ?: return null
        val settings = wireguard.getAsJsonObject("settings") ?: return null
        val peers = settings.getAsJsonArray("peers") ?: JsonArray().also { settings.add("peers", it) }
        val peer = peers.firstOrNull()?.asJsonObject ?: com.google.gson.JsonObject().also { peers.add(it) }
        val host = if (endpoint.host.contains(':') && !endpoint.host.startsWith("[")) {
            "[${endpoint.host}]"
        } else endpoint.host
        peer.addProperty("endpoint", "$host:${endpoint.port}")
        wireguard.getAsJsonObject("streamSettings")?.let { stream ->
            if (finalMask.isNullOrBlank()) stream.remove("finalmask")
            else JsonParser.parseString(finalMask).takeIf { it.isJsonObject }?.let { stream.add("finalmask", it) }
        }
        root.toString()
    }.getOrNull()

    private fun persist(guid: String, original: ProfileItem, endpoint: WarpEndpointTester.Endpoint) {
        MmkvManager.encodeServerConfig(guid, original.copy(
            server = endpoint.host,
            serverPort = endpoint.port.toString(),
            warpWireGuardSelectedEndpoint = "${endpoint.host}:${endpoint.port}",
            description = WarpWireGuardConfig.DESCRIPTION,
            remarks = original.remarks.takeIf { it.isNotBlank() && !it.equals("WireGuard", true) }
                ?: "WARP WireGuard",
        ))
    }

    private fun parse(raw: String): List<WarpEndpointTester.Endpoint> = raw.split(',', ';', '\n', '\r')
        .mapNotNull(::endpoint)

    private fun endpoint(raw: String?): WarpEndpointTester.Endpoint? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        val split = if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close <= 0) return null
            text.substring(1, close) to text.substring(close + 1).removePrefix(":")
        } else {
            val index = text.lastIndexOf(':')
            if (index <= 0) return null
            text.substring(0, index) to text.substring(index + 1)
        }
        val port = split.second.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return WarpEndpointTester.Endpoint(split.first, port)
    }
}
