package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.filterPingableGuids
import com.v2ray.ang.core.isProfilePingable
import com.v2ray.ang.core.WarpMasqueBridge
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.SoarBridge
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.PingNgDesyncManager
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.core.canProbeOpenVpnProfile
import com.v2ray.ang.core.containsUnsupportedJoinedIpv4Address
import com.v2ray.ang.core.shouldUseV2rayNgRealDelayProbe
import com.v2ray.ang.core.shouldUseV2rayNgTcpPreflight
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.OpenVpnCredentialStore
import com.v2ray.ang.handler.OpenVpnCredentials
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()
    private val warpMasqueMutex = Mutex()
    private val openVpnMutex = Mutex()
    private val desyncProbeLock = Any()

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Match v2rayNG's configured worker concurrency. PingNG's Custom profiles need
        // serialized native measurements, and MASQUE profiles share one bridge instance.
        return when (configType) {
            EConfigType.CUSTOM -> customConfigMutex.withLock { block() }
            EConfigType.WARP -> warpMasqueMutex.withLock { block() }
            else -> block()
        }
    }

    suspend fun <T> runOpenVpn(block: suspend () -> T): T {
        openVpnMutex.lock()
        return try {
            block()
        } finally {
            openVpnMutex.unlock()
        }
    }

    /** The native Desync parser is process-global; temporary candidate probes must not overlap. */
    fun <T> runDesyncProbe(block: () -> T): T = synchronized(desyncProbeLock, block)
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val pingableGuids = filterPingableGuids(
        guids,
        includeOpenVpnProfiles = !onlyTcp,
        profileForGuid = MmkvManager::decodeServerConfig,
    )
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(if (onlyTcp) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = pingableGuids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = try {
                        if (onlyTcp) startTcping(guid) else startRealPing(guid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val isOpenVpn = !onlyTcp && runCatching {
                            MmkvManager.decodeServerConfig(guid)?.configType == EConfigType.OPENVPN
                        }.getOrDefault(false)
                        if (!isOpenVpn) throw error
                        PingNgDiagnostics.record(
                            "OpenVPN real-delay probe failed for profile $guid",
                            error,
                        )
                        -1L
                    }
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.OPENVPN) {
            return RealPingExecutionLimiter.runOpenVpn {
                if (canProbeOpenVpnProfile(
                        guid,
                        MmkvManager.getSelectServer(),
                        CoreServiceManager.isOpenVpnActive(),
                    )
                ) {
                    measureOpenVpnTunnelDelay().also { delay ->
                        PingNgDiagnostics.record("OpenVPN tunnel real-delay probe completed: ${delay}ms")
                    }
                } else {
                    measureOpenVpnProfileDelay(guid, config)
                }
            }
        }
        if (SoarBridge.isProfile(config)) {
            val isSelectedLiveProfile = guid == MmkvManager.getSelectServer() && CoreServiceManager.isRunning()
            return measureSoarPing(guid, config, isSelectedLiveProfile)
        }
        if (!isProfilePingable(config)) return retFailure
        val isSelectedLiveProfile = guid == MmkvManager.getSelectServer() && CoreServiceManager.isRunning()
        val useV2rayNgProbe = shouldUseV2rayNgRealDelayProbe(config)

        // DNSTT exposes its own local SOCKS listener. Probe that listener
        // directly so the result measures the DNS/Noise + SSH/SOCKS path and
        // does not depend on CoreNativeManager's separate Xray test instance.
        if (config.description == MasterDnsBridge.DNSTT) {
            return measureDnsttPing(guid, config, isSelectedLiveProfile)
        }

        // v2rayNG 2.3.12 first skips ordinary profiles whose server TCP port
        // cannot be reached. Keep this outside the serialized native-probe lock.
        if (shouldUseV2rayNgTcpPreflight(config)) {
            val port = config.serverPort?.toIntOrNull() ?: return retFailure
            if (SpeedtestManager.socketConnectTime(config.server.orEmpty(), port, 1_000) <= -1L) {
                return retFailure
            }
        }

        return RealPingExecutionLimiter.run(config.configType) {
            // Probe the live local SOCKS inbound first for the active profile. This follows
            // the exact running Xray route, including PingNG Desync, instead of a standalone
            // probe that can return -1 while ordinary traffic is healthy.
            if (isSelectedLiveProfile && !useV2rayNgProbe) {
                val liveUrls = SpeedtestManager.delayProbeUrls(
                    SettingsManager.getDelayTestUrl(),
                    SettingsManager.getDelayTestUrl(true),
                ).take(3)
                liveUrls.forEachIndexed { index, url ->
                    val liveDelay = SpeedtestManager.liveTunnelDelay(url)
                    if (liveDelay >= 0L) {
                        if (index > 0) PingNgDiagnostics.record("Live tunnel delay succeeded on fallback endpoint ${index + 1}")
                        return@run liveDelay
                    }
                }
            }

            val measureWithGeneratedConfig = {
                val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                if (!configResult.status) {
                    PingNgDiagnostics.record(
                        "Speedtest config generation failed for profile $guid: ${configResult.errorMessage}"
                    )
                    retFailure
                } else if (containsUnsupportedJoinedIpv4Address(configResult.content)) {
                    val message = "Skipped real-ping for profile $guid: generated config contains an unsupported '+'-joined IPv4 address"
                    LogUtil.w(AppConfig.TAG, message)
                    PingNgDiagnostics.record(message)
                    retFailure
                } else {
                    val ownsMasque = config.configType == EConfigType.WARP && !isSelectedLiveProfile
                    val ownsMasterDns = MasterDnsBridge.isProfile(config) && !isSelectedLiveProfile
                    if (ownsMasterDns && MasterDnsBridge.isActive()) {
                        retFailure
                    } else {
                        if (ownsMasque) WarpMasqueBridge.startIfNeeded(context, guid, config)
                        val measure = {
                            if (ownsMasterDns) MasterDnsBridge.start(context, guid, config)
                            try {
                                // Match the upstream 2.3.12 real-delay target for ordinary
                                // profiles. PingNG-owned bridges keep bounded fallback URLs.
                                val probeUrls = if (useV2rayNgProbe) {
                                    listOf(SettingsManager.getDelayTestUrl())
                                } else {
                                    SpeedtestManager.delayProbeUrls(
                                        SettingsManager.getDelayTestUrl(),
                                        SettingsManager.getDelayTestUrl(true),
                                    ).take(3)
                                }
                                probeUrls.firstNotNullOfOrNull { url ->
                                    CoreNativeManager.measureOutboundDelay(configResult.content, url)
                                        .takeIf { it >= 0L }
                                } ?: retFailure
                            } finally {
                                if (ownsMasterDns) MasterDnsBridge.stop()
                            }
                        }
                        try {
                            if (ownsMasterDns) synchronized(MasterDnsBridge) { measure() } else measure()
                        } finally {
                            if (ownsMasque) WarpMasqueBridge.stop()
                        }
                    }
                }
            }
            val hasDesync = PingNgCompat.isNativeDesyncEnabled(config)
            if (!hasDesync) {
                measureWithGeneratedConfig()
            } else {
                RealPingExecutionLimiter.runDesyncProbe {
                    // PingNG's JNI parser and active listener are process-global.
                    // Hold the manager monitor through config generation and the
                    // probe so a service start/reload cannot replace this port.
                    synchronized(PingNgDesyncManager) {
                        val alreadyRunning = PingNgDesyncManager.activePort() in 1..65535
                        val temporaryPort = if (alreadyRunning) {
                            0
                        } else {
                            runCatching { PingNgDesyncManager.start(config) }
                                .onFailure { error ->
                                    PingNgDiagnostics.record("Could not start Desync for a real-delay probe", error)
                                }
                                .getOrDefault(0)
                        }
                        if (!alreadyRunning && temporaryPort !in 1..65535) {
                            retFailure
                        } else {
                            if (!alreadyRunning) {
                                PingNgDiagnostics.record("Temporary Desync started for real-delay profile $guid")
                            }
                            try {
                                measureWithGeneratedConfig()
                            } finally {
                                if (!alreadyRunning) PingNgDesyncManager.stop()
                            }
                        }
                    }
                }
            }
        }
    }

    /** OpenVPN owns the Android TUN directly, so its delay must use the app's routed HTTP path. */
    private suspend fun measureOpenVpnTunnelDelay(): Long = coroutineScope {
        val urls = SpeedtestManager.delayProbeUrls(
            SettingsManager.getDelayTestUrl(),
            SettingsManager.getDelayTestUrl(true),
            SpeedtestManager.getRemoteIpInfoUrl(),
            "https://cp.cloudflare.com/generate_204",
        )
        val probes = urls.map { url ->
            async(Dispatchers.IO) { SpeedtestManager.directTunnelDelay(url, timeoutMs = 5_000) }
        }
        try {
            probes.awaitAll().firstOrNull { it >= 0L } ?: -1L
        } finally {
            probes.forEach { it.cancel() }
        }
    }

    /** Measures a profile's own OpenVPN control handshake when its tunnel is not already live. */
    private fun measureOpenVpnProfileDelay(
        guid: String,
        profile: com.v2ray.ang.dto.entities.ProfileItem,
    ): Long {
        val openVpnConfig = profile.openVpnConfig?.takeIf(String::isNotBlank) ?: return -1L
        val credentials = OpenVpnCredentialStore.load(context, guid) ?: OpenVpnCredentials()
        val requirements = OpenVpnProfileCodec.credentialRequirements(
            openVpnConfig,
            credentials.privateKeyPem,
        )
        val hasPemIdentity = !credentials.disableClientCertificate &&
            credentials.clientCertificatePem.isNotBlank() &&
            credentials.privateKeyPem.isNotBlank()
        val missingCredentials =
            (requirements.usernamePassword &&
                (credentials.username.isBlank() || credentials.password.isBlank())) ||
                (requirements.privateKeyPassphrase && credentials.privateKeyPassphrase.isBlank()) ||
                (requirements.externalPki && !credentials.disableClientCertificate &&
                    !hasPemIdentity && credentials.externalPkiAlias.isBlank())
        if (missingCredentials) {
            // Do not reject a profile before testing it. The profile may not actually
            // require every credential inferred from its directives; let OpenVPN 3
            // perform the real TCP/UDP handshake and report the native result.
            PingNgDiagnostics.record(
                "OpenVPN real-delay probe profile $guid has incomplete saved credentials; attempting native handshake",
            )
        }

        val protocol = OpenVpnProfileCodec.detect(openVpnConfig).protocol
        val delay = OpenVpn3Native.probeHandshakeDelayMillis(
            profileGuid = guid,
            profile = OpenVpnProfileCodec.runtimeConfig(
                openVpnConfig,
                clientCertificatePem = credentials.clientCertificatePem.takeUnless {
                    credentials.disableClientCertificate
                }.orEmpty(),
                privateKeyPem = credentials.privateKeyPem.takeUnless {
                    credentials.disableClientCertificate
                }.orEmpty(),
            ),
            username = credentials.username,
            password = credentials.password,
            privateKeyPassphrase = credentials.privateKeyPassphrase,
            externalPkiAlias = credentials.externalPkiAlias
                .takeIf { requirements.externalPki && !hasPemIdentity }.orEmpty(),
            disableClientCertificate = credentials.disableClientCertificate && requirements.externalPki,
            context = context,
            socketProtect = { socket -> CoreServiceManager.protectOpenVpnProbeSocket(socket, guid) },
        )
        PingNgDiagnostics.record(
            "OpenVPN profile real-delay probe transport=${protocol.lowercase()} profile=$guid result=${delay}ms",
        )
        return delay
    }

    private suspend fun measureDnsttPing(
        guid: String,
        config: com.v2ray.ang.dto.entities.ProfileItem,
        isSelectedLiveProfile: Boolean,
    ): Long = RealPingExecutionLimiter.run(config.configType) {
        synchronized(MasterDnsBridge) {
            val ownsBridge = !isSelectedLiveProfile
            if (ownsBridge && MasterDnsBridge.isActive()) return@synchronized -1L
            if (!ownsBridge && !MasterDnsBridge.isActive()) return@synchronized -1L

            try {
                if (ownsBridge) MasterDnsBridge.start(context, guid, config)
                val socksPort = MasterDnsBridge.outboundPort(config)
                val primary = SpeedtestManager.liveTunnelDelay(
                    SettingsManager.getDelayTestUrl(),
                    timeoutMs = 5_000,
                    socksPortOverride = socksPort,
                )
                if (primary >= 0L) primary else SpeedtestManager.liveTunnelDelay(
                    SettingsManager.getDelayTestUrl(true),
                    timeoutMs = 5_000,
                    socksPortOverride = socksPort,
                )
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "DNSTT real-ping probe failed: ${e.message.orEmpty()}")
                -1L
            } finally {
                if (ownsBridge) MasterDnsBridge.stop()
            }
        }
    }

    /** Measures the actual SoarDNS tunnel through its local SOCKS5 listener. */
    private fun measureSoarPing(
        guid: String,
        config: com.v2ray.ang.dto.entities.ProfileItem,
        isSelectedLiveProfile: Boolean,
    ): Long = synchronized(SoarBridge) {
        val ownsBridge = !isSelectedLiveProfile
        if (ownsBridge && SoarBridge.isActive()) return@synchronized -1L
        if (!ownsBridge && !SoarBridge.isActive()) return@synchronized -1L

        try {
            if (ownsBridge) SoarBridge.start(context, guid, config)
            val socksPort = SoarBridge.outboundPort(config).takeIf { ownsBridge }
            SpeedtestManager.delayProbeUrls(
                SettingsManager.getDelayTestUrl(),
                SettingsManager.getDelayTestUrl(true),
                SpeedtestManager.getRemoteIpInfoUrl(),
            ).take(3).firstNotNullOfOrNull { url ->
                SpeedtestManager.liveTunnelDelay(url, timeoutMs = 5_000, socksPortOverride = socksPort)
                    .takeIf { it >= 0L }
            } ?: -1L
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "SoarDNS real-ping probe failed: ${error.message.orEmpty()}")
            -1L
        } finally {
            if (ownsBridge) SoarBridge.stop()
        }
    }

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (SoarBridge.isProfile(config)) {
            val isSelectedLiveProfile = guid == MmkvManager.getSelectServer() && CoreServiceManager.isRunning()
            return measureSoarPing(guid, config, isSelectedLiveProfile)
        }
        if (!isProfilePingable(config)) return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.configType != EConfigType.WARP
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)

            return tcpTime
        }

        return retFailure
    }
}
