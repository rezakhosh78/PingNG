package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Tests WARP Plus endpoint pairs before the selected profile is started. */
object WarpEndpointTester {
    data class Endpoint(val host: String, val port: Int)
    private data class PairResult(
        val pair: Pair<Endpoint, Endpoint>,
        val delay: Long,
    )
    // Libv2ray's standalone delay probe uses a process-global Go channel and
    // is not safe to run concurrently. Keep all candidate probes serialized
    // on the caller coroutine; moving the Go call to a second Java executor
    // can leave the native dialer attached to a stale thread and make every
    // following candidate fail.
    private val nativeProbeMutex = Mutex()
    private const val PREVIOUS_PAIR_TIMEOUT_MS = 4_000L
    private const val SEARCH_PAIR_TIMEOUT_MS = 3_000L
    // Endpoint verification must survive a few native dial timeouts. The
    // probe itself remains capped at three seconds; the total budget is
    // calculated from the number of candidates queued for verification.
    private const val SEARCH_DEADLINE_MS = 60_000L
    private const val DISCOVERY_HITS_TO_VERIFY = 128
    private const val MEDIUM_DISCOVERY_STOP_HITS = 16
    private const val SLOW_PAIR_LIMIT = 256
    // WARPSCOUT's phase-2 target. The app's native delay probe supplies the
    // tunnel, while this URL verifies that traffic actually crosses it. Use
    // HTTP here: the device log showed HTTPS/TLS handshakes timing out even
    // while the WARP chain accepted ordinary TCP/UDP traffic, which caused a
    // healthy endpoint to be reported as failed.
    private const val SEARCH_PROBE_URL = "http://cp.cloudflare.com/generate_204"
    private const val SEARCH_PROBE_URL_FALLBACK = "http://connectivitycheck.gstatic.com/generate_204"
    private const val WARPSCOUT_SAMPLE_HOSTS_PER_SUBNET = 12

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

        val mode = WarpPlusConfig.normalizeEndpointTestMode(parent.warpEndpointTestMode)
        if (mode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM) {
            // Custom mode is explicit configuration, not a search mode. The
            // visible Server/Endpoint and Port fields are authoritative;
            // never let the default candidate-list text replace them.
            val customInner = parseEndpoint(inner)
            val customOuter = parseEndpoint(outer)
            if (customInner.host.isBlank() || customOuter.host.isBlank()) return false
            val configuredFinalMask = if (parent.warpFinalMaskEnabled == false) {
                null
            } else {
                outer.finalMask?.takeIf { it.isNotBlank() } ?: WarpPlusConfig.DEFAULT_FINAL_MASK
            }
            MmkvManager.encodeServerConfig(
                innerGuid,
                inner.copy(server = customInner.host, serverPort = customInner.port.toString()),
            )
            MmkvManager.encodeServerConfig(
                outerGuid,
                outer.copy(
                    server = customOuter.host,
                    serverPort = customOuter.port.toString(),
                    finalMask = configuredFinalMask,
                ),
            )
            // Custom is explicit user configuration. Never scan or health-check
            // it here; save exactly what the user entered and let the normal
            // start path establish the connection directly.
            MmkvManager.encodeServerConfig(
                parentGuid,
                parent.copy(
                    warpEndpointTestMode = mode,
                    warpSelectedEndpoint = "inner ${customInner.host}:${customInner.port}  •  outer ${customOuter.host}:${customOuter.port}",
                ),
            )
            return true
        }

        // A previously verified pair is stored in both child profiles. It must
        // still get a short health check on every reconnect: an endpoint can be
        // reachable during setup and fail later because UDP filtering or the
        // upstream route changed. If it fails, the search below tries a fresh
        // candidate instead of making the user disconnect/reconnect manually.
        val previousPair = parent.warpSelectedEndpoint?.let(::parseSelectedPair)
        if (previousPair != null) onProgress("WARP Plus Reconnect 0/1")

        val endpointPool = when (mode) {
            WarpPlusConfig.ENDPOINT_MODE_FAST -> parse(WarpPlusConfig.FAST_ENDPOINTS)
            WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
            WarpPlusConfig.ENDPOINT_MODE_SLOW -> parse(WarpPlusConfig.ALL_ENDPOINTS)
            else -> emptyList()
        }.distinct().shuffled()
        if (endpointPool.isEmpty()) return false
        // WARP Plus is a two-hop WireGuard chain. Both hops must be discovered
        // with their own key pair; using the inner key for the outer pool made
        // the old search select endpoints that only answered the wrong hop.
        val innerEndpoints = endpointPool.shuffled()
        val outerEndpoints = endpointPool.shuffled()
        PingNgDiagnostics.record(
            "WARP Plus ${mode} search started: inner=${innerEndpoints.size}, outer=${outerEndpoints.size}",
        )
        onProgress(
            if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
                "WARP Plus discovery: outer 0/${outerEndpoints.size}"
            } else {
                "WARP Plus discovery: inner 0/${innerEndpoints.size}"
            },
        )

        val result = CoreConfigManager.getV2rayConfig(context, parentGuid)
        if (!result.status || result.content.isBlank()) return false
        CoreNativeManager.initCoreEnv(context)
        val selectedModeFinalMask = when (mode) {
            WarpPlusConfig.ENDPOINT_MODE_FAST -> parent.warpFastFinalMask
            WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
            WarpPlusConfig.ENDPOINT_MODE_SLOW -> parent.warpAllFinalMask
            else -> null
        }
        val defaultFinalMask = if (parent.warpFinalMaskEnabled == false) {
            null
        } else {
            selectedModeFinalMask?.takeIf { it.isNotBlank() }
                ?: WarpPlusConfig.DEFAULT_FINAL_MASK
        }

        // Reconnect optimization must happen before the large discovery pass.
        // The last pair gets a four-second real-data check; only failure
        // is allowed to enter the fresh endpoint search.
        if (previousPair != null) {
            PingNgDiagnostics.record("WARP reconnect probe: ${formatPair(previousPair)}")
            val reconnectStartedAt = System.nanoTime()
            val previousDelay = probePair(
                result.content,
                previousPair,
                PREVIOUS_PAIR_TIMEOUT_MS,
                finalMask = defaultFinalMask,
                probeUrl = warpReadinessUrl(),
                allowFallback = false,
            )
            val reconnectElapsedMs = (System.nanoTime() - reconnectStartedAt) / 1_000_000L
            val reconnectWithinBudget = reconnectElapsedMs <= PREVIOUS_PAIR_TIMEOUT_MS
            onProgress("WARP Plus Reconnect 1/1")
            PingNgDiagnostics.record(
                "WARP reconnect probe result: ${previousDelay}ms; " +
                    "elapsed=${reconnectElapsedMs}ms; limit=${PREVIOUS_PAIR_TIMEOUT_MS}ms",
            )
            if (previousDelay >= 0L && reconnectWithinBudget) {
                MmkvManager.encodeServerConfig(
                    innerGuid,
                    inner.copy(
                        server = previousPair.first.host,
                        serverPort = previousPair.first.port.toString(),
                    ),
                )
                MmkvManager.encodeServerConfig(
                    outerGuid,
                    outer.copy(
                        server = previousPair.second.host,
                        serverPort = previousPair.second.port.toString(),
                        finalMask = defaultFinalMask,
                    ),
                )
                MmkvManager.encodeServerConfig(
                    parentGuid,
                    parent.copy(
                        warpEndpointTestMode = mode,
                        warpSelectedEndpoint = formatPair(previousPair),
                    ),
                )
                return true
            }
            PingNgDiagnostics.record(
                "WARP reconnect probe rejected; trying the Fast fallback before discovery",
            )
        }

        // On a fresh Fast profile, or when the saved pair fails, try the
        // requested fixed endpoint. A healthy saved pair never comes here.
        if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST) {
            val fixed = Endpoint("188.114.96.206", 878)
            val fixedPair = fixed to fixed
            if (previousPair != fixedPair) {
                onProgress("WARP Plus Fast: testing 188.114.96.206:878 on Inner + Outer")
                val startedAt = System.nanoTime()
                val fixedDelay = probePair(
                    result.content,
                    fixedPair,
                    timeoutMs = PREVIOUS_PAIR_TIMEOUT_MS,
                    finalMask = defaultFinalMask,
                    probeUrl = warpReadinessUrl(),
                    allowFallback = false,
                )
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                PingNgDiagnostics.record("WARP Plus fixed pair: ${fixedDelay}ms in ${elapsedMs}ms")
                if (fixedDelay >= 0L && elapsedMs <= PREVIOUS_PAIR_TIMEOUT_MS) {
                    MmkvManager.encodeServerConfig(
                        innerGuid,
                        inner.copy(server = fixed.host, serverPort = fixed.port.toString()),
                    )
                    MmkvManager.encodeServerConfig(
                        outerGuid,
                        outer.copy(server = fixed.host, serverPort = fixed.port.toString(), finalMask = defaultFinalMask),
                    )
                    MmkvManager.encodeServerConfig(
                        parentGuid,
                        parent.copy(warpEndpointTestMode = mode, warpSelectedEndpoint = formatPair(fixedPair)),
                    )
                    return true
                }
            }
            onProgress("WARP Plus Fast: fixed endpoint failed; searching candidates")
        }

        // Scan randomized pools for both hops. Fast keeps enough discovery
        // hits to try both same and different combinations. Medium samples a
        // small set from Outer only, ranks them by handshake latency, and
        // verifies the same-endpoint pairs for both hops.
        // Slow intentionally scans the complete pool for separate, different
        // inner/outer endpoints.
        val innerDiscoveryIdentity = WarpScoutEndpointScanner.Identity(
            privateKey = inner.secretKey.orEmpty(),
            peerPublicKey = inner.publicKey.orEmpty(),
        )
        val outerDiscoveryIdentity = WarpScoutEndpointScanner.Identity(
            privateKey = outer.secretKey.orEmpty(),
            peerPublicKey = outer.publicKey.orEmpty(),
        )
        val discoveryRate = if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) 3_500L else 1_500L
        suspend fun discover(
            endpoints: List<Endpoint>,
            identity: WarpScoutEndpointScanner.Identity,
            label: String,
        ): List<WarpScoutEndpointScanner.Hit> {
            val primaryHits = WarpScoutEndpointScanner.scan(
                endpoints = endpoints,
                identity = identity,
                ratePerSecond = discoveryRate,
                maxHits = DISCOVERY_HITS_TO_VERIFY,
                stopAfterHits = if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
                    MEDIUM_DISCOVERY_STOP_HITS
                } else {
                    null
                },
                onProgress = { tested, total -> onProgress("WARP Plus discovery: $label $tested/$total") },
            )
            if (primaryHits.isNotEmpty() || mode == WarpPlusConfig.ENDPOINT_MODE_FAST) {
                return primaryHits
            }

            // WARPSCOUT does not pay every extended-port timeout for every
            // address. It samples addresses first, remembers which alternate
            // ports answered, and only then sweeps those ports across the
            // complete pool. This is the important speed/accuracy tradeoff
            // missing from the previous large-pool implementation.
            val sample = buildWarpscoutPool(
                WarpPlusConfig.WARPSCOUT_EXTENDED_ENDPOINT_PORTS,
                WARPSCOUT_SAMPLE_HOSTS_PER_SUBNET,
            )
            onProgress("WARP Plus discovery: $label alternate-port sample 0/${sample.size}")
            val sampleHits = WarpScoutEndpointScanner.scan(
                endpoints = sample,
                identity = identity,
                ratePerSecond = discoveryRate,
                maxHits = DISCOVERY_HITS_TO_VERIFY,
                stopAfterHits = if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
                    MEDIUM_DISCOVERY_STOP_HITS
                } else {
                    null
                },
                onProgress = { tested, total ->
                    onProgress("WARP Plus discovery: $label alternate-port sample $tested/$total")
                },
            )
            val reachablePorts = sampleHits.map { it.endpoint.port }.distinct()
            if (reachablePorts.isEmpty()) return emptyList()
            val extendedPool = buildWarpscoutPool(reachablePorts, null).shuffled()
            onProgress("WARP Plus discovery: $label extended sweep 0/${extendedPool.size}")
            return WarpScoutEndpointScanner.scan(
                endpoints = extendedPool,
                identity = identity,
                ratePerSecond = discoveryRate,
                maxHits = DISCOVERY_HITS_TO_VERIFY,
                stopAfterHits = if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
                    MEDIUM_DISCOVERY_STOP_HITS
                } else {
                    null
                },
                onProgress = { tested, total ->
                    onProgress("WARP Plus discovery: $label extended sweep $tested/$total")
                },
            )
        }
        val innerDiscoveryHits: List<WarpScoutEndpointScanner.Hit>
        val outerDiscoveryHits: List<WarpScoutEndpointScanner.Hit>
        if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
            // An Outer handshake does not prove that this endpoint works with
            // the Inner account. Check the shortlisted Outer hits against the
            // Inner identity before forming same-endpoint pairs.
            outerDiscoveryHits = discover(outerEndpoints, outerDiscoveryIdentity, "outer")
            val innerShortlist = outerDiscoveryHits.map { it.endpoint }.distinct()
            innerDiscoveryHits = if (innerShortlist.isEmpty()) emptyList() else {
                onProgress("WARP Plus discovery: inner 0/${innerShortlist.size}")
                WarpScoutEndpointScanner.scan(
                    endpoints = innerShortlist,
                    identity = innerDiscoveryIdentity,
                    ratePerSecond = discoveryRate,
                    maxHits = DISCOVERY_HITS_TO_VERIFY,
                    onProgress = { tested, total ->
                        onProgress("WARP Plus discovery: inner $tested/$total")
                    },
                )
            }
        } else {
            innerDiscoveryHits = discover(innerEndpoints, innerDiscoveryIdentity, "inner")
            outerDiscoveryHits = discover(outerEndpoints, outerDiscoveryIdentity, "outer")
        }
        val discoveredOuter = outerDiscoveryHits
            .let { hits ->
                if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
                    hits.sortedBy { it.latencyMs }
                } else {
                    hits.sortedBy { it.discoveryOrder }
                }
            }
            .map { it.endpoint }
            .distinct()
        val discoveredInner = innerDiscoveryHits
            .sortedBy { it.discoveryOrder }
            .map { it.endpoint }
            .distinct()
        onProgress(
            "WARP Plus discovery: inner=${discoveredInner.size}, outer=${discoveredOuter.size} candidates",
        )
        val candidatePairs = buildCandidatePairs(
            mode,
            innerEndpoints,
            outerEndpoints,
            discoveredInner,
            discoveredOuter,
        )
        // The saved pair already used its four-second reconnect window.
        // Verify counts only new pairs formed from this scan's real hits.
        val fixedPair = Endpoint("188.114.96.206", 878).let { it to it }
        val pairsToProbe = candidatePairs.distinct().filterNot {
            it == previousPair || (mode == WarpPlusConfig.ENDPOINT_MODE_FAST && it == fixedPair)
        }
        if (pairsToProbe.isEmpty()) {
            PingNgDiagnostics.record("WARP Plus ${mode} discovery produced no dual-hop pairs")
        } else {
            onProgress("WARP Plus Verify 0/${pairsToProbe.size} (inner ${discoveredInner.size}, outer ${discoveredOuter.size})")
        }
        // Every endpoint pair built from successful Scan hits must remain in
        // Verify. Give the serial native probe enough time for the full queue;
        // Fast still returns as soon as one usable pair is confirmed.
        val verifyDeadlineMs = if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST ||
            mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM
        ) {
            maxOf(
                SEARCH_DEADLINE_MS,
                pairsToProbe.size.toLong() * (SEARCH_PAIR_TIMEOUT_MS + 1_000L) + 5_000L,
            )
        } else {
            SEARCH_DEADLINE_MS
        }

        // The previous pair already failed its four-second probe above; it
        // is excluded from this fresh queue so the counter reflects exactly
        // the candidates that will be measured.
        var selected = if (pairsToProbe.isEmpty()) null else probeFirstSuccessful(
            pairsToProbe,
            result.content,
            finalMask = defaultFinalMask,
            probeUrl = warpReadinessUrl(),
            allowFallback = true,
            searchDeadlineMs = verifyDeadlineMs,
            chooseFastest = false,
            onProgress = { tested, total -> onProgress("WARP Plus Verify $tested/$total") },
        )
        if (selected == null && mode != WarpPlusConfig.ENDPOINT_MODE_SLOW) {
            // UDP discovery can miss a usable endpoint because the handshake
            // packet is filtered while a complete tunnel still works. This
            // is a separate real-tunnel fallback, not part of Verify's count.
            // The previous and fixed pair already had their own timed probes.
            val fallbackEndpoints = if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
                discoveredOuter.ifEmpty { outerEndpoints.take(24) }
            } else {
                parse(WarpPlusConfig.FAST_ENDPOINTS)
            }
            val fallbackPairs = fallbackEndpoints.distinct()
                .map { it to it }
                .filterNot { it == previousPair || it in pairsToProbe ||
                    (mode == WarpPlusConfig.ENDPOINT_MODE_FAST && it == fixedPair) }
            if (fallbackPairs.isNotEmpty()) {
                PingNgDiagnostics.record("WARP Plus ${mode} trying ${fallbackPairs.size} curated real-tunnel fallbacks")
                onProgress("WARP Plus fallback 0/${fallbackPairs.size}")
                selected = probeFirstSuccessful(
                    fallbackPairs,
                    result.content,
                    finalMask = defaultFinalMask,
                    probeUrl = warpReadinessUrl(),
                    allowFallback = false,
                    searchDeadlineMs = maxOf(SEARCH_DEADLINE_MS,
                        fallbackPairs.size.toLong() * SEARCH_PAIR_TIMEOUT_MS + 5_000L),
                    onProgress = { tested, total -> onProgress("WARP Plus fallback $tested/$total") },
                )
            }
        }
        currentCoroutineContext().ensureActive()
        val best = selected ?: return false
        val (bestInner, bestOuter) = best.pair
        MmkvManager.encodeServerConfig(innerGuid, inner.copy(server = bestInner.host, serverPort = bestInner.port.toString()))
        MmkvManager.encodeServerConfig(
            outerGuid,
            outer.copy(
                server = bestOuter.host,
                serverPort = bestOuter.port.toString(),
                finalMask = defaultFinalMask,
            ),
        )
        MmkvManager.encodeServerConfig(
            parentGuid,
            parent.copy(
                warpEndpointTestMode = mode,
                warpSelectedEndpoint = "inner ${bestInner.host}:${bestInner.port}  •  outer ${bestOuter.host}:${bestOuter.port}",
            ),
        )
        PingNgDiagnostics.record("WARP Plus ${mode} selected ${formatPair(best.pair)}; starting core")
        return true
    }

    private suspend fun probeFirstSuccessful(
        candidates: List<Pair<Endpoint, Endpoint>>,
        rawConfig: String,
        timeoutMs: Long = SEARCH_PAIR_TIMEOUT_MS,
        clearFinalMask: Boolean = false,
        probeUrl: String? = null,
        allowFallback: Boolean = true,
        finalMask: String? = null,
        searchDeadlineMs: Long? = null,
        chooseFastest: Boolean = false,
        onProgress: suspend (tested: Int, total: Int) -> Unit = { _, _ -> },
    ): PairResult? {
        val result = WarpSearchCore.search(
            candidates.map { WarpSearchCore.Candidate(it.first, it.second) },
            WarpSearchCore.Options(
                timeoutMs = timeoutMs,
                deadlineMs = searchDeadlineMs ?: SEARCH_DEADLINE_MS,
                // Libv2ray's delay call is not cancellation-safe. One native
                // worker prevents cancelled probes from keeping the next VPN
                // start blocked behind the global native channel.
                workers = 1,
                chooseFastest = chooseFastest,
            ),
            probe = { candidate, probeTimeoutMs ->
            probePair(
                rawConfig,
                candidate.inner to candidate.outer,
                probeTimeoutMs,
                finalMask = finalMask,
                clearFinalMask = clearFinalMask,
                probeUrl = probeUrl,
                allowFallback = allowFallback,
            )
            },
            onCandidateCompleted = onProgress,
        )
        return result?.let { PairResult(it.candidate.inner to it.candidate.outer, it.delay) }
    }

    /** Probe the lightweight search URL first, then use configured URLs as fallbacks. */
    private suspend fun probePair(
        rawConfig: String,
        pair: Pair<Endpoint, Endpoint>,
        timeoutMs: Long,
        finalMask: String? = null,
        clearFinalMask: Boolean = false,
        probeUrl: String? = null,
        allowFallback: Boolean = true,
    ): Long {
        val effectiveUrl = probeUrl ?: warpReadinessUrl()
        val probeUrls = buildProbeUrls(effectiveUrl, allowFallback)
        val startedAt = System.nanoTime()
        // Libv2ray's delay call blocks its calling thread and cannot be
        // interrupted by a coroutine timeout. withTimeoutOrNull used to turn
        // a successful late native answer into -1, then Verify displayed
        // "no endpoint" just before that same endpoint came online. Wait for
        // the actual result and enforce the short reconnect budget at its
        // call site; cancellation still propagates after the native call.
        return nativeProbeMutex.withLock {
            val config = runCatching {
                configForPair(rawConfig, pair, finalMask, clearFinalMask)
            }.getOrNull() ?: return@withLock -1L
            var lastDelay = -1L
            for (url in probeUrls) {
                currentCoroutineContext().ensureActive()
                if ((System.nanoTime() - startedAt) / 1_000_000L >= timeoutMs) break
                val measured = runCatching {
                    CoreNativeManager.measureOutboundDelay(config, url)
                }.getOrDefault(-1L)
                currentCoroutineContext().ensureActive()
                lastDelay = measured
                if (measured >= 0L) break
            }
            lastDelay
        }
    }

    private fun configForPair(
        raw: String,
        pair: Pair<Endpoint, Endpoint>,
        finalMask: String? = null,
        clearFinalMask: Boolean = false,
    ): String {
        val root = JsonParser.parseString(raw).asJsonObject
        val wireguards = root.getAsJsonArray("outbounds")?.filter { item ->
            item.isJsonObject && item.asJsonObject.get("protocol")?.asString == "wireguard"
        }.orEmpty()
        setEndpoint(wireguards.getOrNull(0)?.asJsonObject, pair.first)
        setEndpoint(wireguards.getOrNull(1)?.asJsonObject, pair.second)
        val outerStreamSettings = wireguards.getOrNull(1)?.asJsonObject
            ?.getAsJsonObject("streamSettings")
        if (clearFinalMask) {
            outerStreamSettings?.remove("finalmask")
        } else if (finalMask != null) {
            JsonParser.parseString(finalMask).takeIf { it.isJsonObject }?.let {
                if (outerStreamSettings != null) outerStreamSettings.add("finalmask", it)
            }
        }
        return root.toString()
    }

    private fun setEndpoint(outbound: com.google.gson.JsonObject?, endpoint: Endpoint) {
        val settings = outbound?.getAsJsonObject("settings") ?: return
        val peers = settings.getAsJsonArray("peers") ?: JsonArray().also { settings.add("peers", it) }
        val first = peers.firstOrNull()?.asJsonObject ?: com.google.gson.JsonObject().also { peers.add(it) }
        first.addProperty("endpoint", formatEndpoint(endpoint))
    }

    private fun parse(raw: String): List<Endpoint> = raw.split(',', ';', '\n', '\r')
        .mapNotNull { value ->
            val text = value.trim()
            val (host, portText) = if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close <= 1) return@mapNotNull null
                text.substring(1, close) to text.substring(close + 1).removePrefix(":")
            } else {
                val separator = text.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                text.substring(0, separator) to text.substring(separator + 1)
            }
            val port = portText.toIntOrNull() ?: return@mapNotNull null
            if (port !in 1..65535) return@mapNotNull null
            Endpoint(host, port)
        }

    private fun buildWarpscoutPool(ports: List<Int>, sampleHostsPerSubnet: Int?): List<Endpoint> =
        WarpPlusConfig.ALL_ENDPOINT_SUBNETS.flatMap { subnet ->
            val hosts = if (sampleHostsPerSubnet == null) {
                (0..255).toList()
            } else {
                (0..255).toList().shuffled().take(sampleHostsPerSubnet.coerceIn(1, 256))
            }
            hosts.flatMap { host -> ports.map { port -> Endpoint("$subnet.$host", port) } }
        }.distinct()

    private fun parseEndpoint(profile: ProfileItem): Endpoint =
        Endpoint(profile.server.orEmpty().removePrefix("[").removeSuffix("]"), profile.serverPort?.toIntOrNull() ?: 2408)

    private fun warpReadinessUrl(): String = SEARCH_PROBE_URL

    internal fun buildCandidatePairs(
        mode: String,
        innerEndpoints: List<Endpoint>,
        outerEndpoints: List<Endpoint>,
        discoveredInner: List<Endpoint>,
        discoveredOuter: List<Endpoint>,
    ): List<Pair<Endpoint, Endpoint>> {
        if (innerEndpoints.isEmpty() || outerEndpoints.isEmpty()) return emptyList()
        if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST) {
            // Verify every same and different pairing of endpoints that
            // answered discovery with their respective hop identities.
            if (discoveredInner.isEmpty() || discoveredOuter.isEmpty()) return emptyList()
            val innerOrder = discoveredInner.distinct()
            val outerOrder = discoveredOuter.distinct()
            val common = innerOrder.filter { it in outerOrder.toSet() }
            val pairs = LinkedHashSet<Pair<Endpoint, Endpoint>>()
            common.forEach { pairs.add(it to it) }
            innerOrder.forEach { inner ->
                outerOrder.forEach { outer -> pairs.add(inner to outer) }
            }
            return pairs.toList()
        }

        if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
            // Same-endpoint candidates must have answered with BOTH keys.
            // The large /24 pool is never inserted into Verify without a hit.
            val innerSet = discoveredInner.toSet()
            return discoveredOuter.filter { it in innerSet }
                .map { endpoint -> endpoint to endpoint }
        }

        // Slow searches independent healthy answers for each hop and excludes
        // identical pairs. The queue is bounded, but discovery itself scans
        // the complete randomized pool before these real-tunnel checks.
        if (discoveredInner.isEmpty() || discoveredOuter.isEmpty()) return emptyList()
        val pairs = LinkedHashSet<Pair<Endpoint, Endpoint>>()
        val innerOrder = discoveredInner.shuffled()
        val outerOrder = discoveredOuter.shuffled()
        outerLoop@ for (outer in outerOrder) {
            for (inner in innerOrder) {
                if (inner == outer) continue
                pairs.add(inner to outer)
                if (pairs.size >= SLOW_PAIR_LIMIT) break@outerLoop
            }
        }
        return pairs.toList()
    }

    private fun buildProbeUrls(primary: String, allowFallback: Boolean): List<String> {
        if (!allowFallback) return listOf(primary)
        return listOfNotNull(
            primary,
            SEARCH_PROBE_URL_FALLBACK,
            // A user-supplied HTTP probe is safe to use as a third option;
            // avoid adding HTTPS fallbacks here because a dead candidate can
            // otherwise spend the whole search deadline in repeated TLS timeouts.
            SettingsManager.getDelayTestUrl()
                .trim()
                .takeIf { it.startsWith("http://", ignoreCase = true) },
        ).distinct()
    }

    private fun parseSelectedPair(value: String): Pair<Endpoint, Endpoint>? {
        val parts = value.split('•').map(String::trim)
        if (parts.size != 2) return null
        val inner = parse(parts[0].substringAfter("inner", "").trim()).firstOrNull()
        val outer = parse(parts[1].substringAfter("outer", "").trim()).firstOrNull()
        return if (inner != null && outer != null) inner to outer else null
    }

    private fun formatPair(pair: Pair<Endpoint, Endpoint>): String =
        "inner ${formatEndpoint(pair.first)} • outer ${formatEndpoint(pair.second)}"

    private fun formatEndpoint(endpoint: Endpoint): String {
        val host = endpoint.host.trim()
        val displayHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "$displayHost:${endpoint.port}"
    }

}
