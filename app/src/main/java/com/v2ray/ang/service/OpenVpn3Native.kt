package com.v2ray.ang.service

import android.os.Build
import androidx.annotation.Keep
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_ExternalPKICertRequest
import net.openvpn.ovpn3.ClientAPI_ExternalPKISignRequest
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import net.openvpn.ovpn3.ClientAPI_RemoteOverride
import net.openvpn.ovpn3.DnsOptions
import net.openvpn.ovpn3.DnsServer
import java.util.concurrent.Executors

/**
 * OpenVPN 3 JNI bindings and Android TUN adapter.
 *
 * The native engine is the OpenSSL-backed Android build from the
 * OpenVPN 3/ics-openvpn project. This class owns the Android-facing callbacks
 * so PingNG continues to use its existing VpnService and notifications.
 */
@Keep
object OpenVpn3Native {
    private val stateLock = ReentrantLock()
    private val stopped = stateLock.newCondition()

    @Volatile
    private var activeClient: AndroidOpenVPNClient? = null

    /** A handshake-only client never owns an Android TUN and may coexist with the live client. */
    @Volatile
    private var probeClient: AndroidOpenVPNClient? = null

    @Volatile
    private var stopRequested = false
    private var stopGeneration = 0L

    init {
        System.loadLibrary("ovpn3")
    }

    @Keep
    fun nativeRun(
        service: CoreVpnService,
        profile: String,
        username: String,
        password: String,
        privateKeyPassphrase: String,
    ): String {
        val client = AndroidOpenVPNClient(service, username, password, privateKeyPassphrase)
        var acquired = false
        var previousStopTimedOut = false
        var canceledDuringWait = false
        val startGeneration = stateLock.withLock { stopGeneration }

        try {
            var remainingNanos = TimeUnit.SECONDS.toNanos(10)
            stateLock.withLock {
                while (OpenVpnLifecyclePolicy.shouldWaitForNativeClientReservation(
                        hasActiveClient = activeClient != null,
                        stopRequested = stopRequested,
                        hasProbeClient = probeClient != null,
                        remainingNanos = remainingNanos,
                        startGeneration = startGeneration,
                        currentGeneration = stopGeneration,
                    )
                ) {
                    remainingNanos = stopped.awaitNanos(remainingNanos)
                }
                if (OpenVpnLifecyclePolicy.mayAcquireNativeClient(
                        hasActiveClient = activeClient != null,
                        hasProbeClient = probeClient != null,
                        startGeneration = startGeneration,
                        currentGeneration = stopGeneration,
                    )
                ) {
                    activeClient = client
                    stopRequested = false
                    acquired = true
                } else {
                    canceledDuringWait = OpenVpnLifecyclePolicy.wasNativeStartCanceled(
                        startGeneration,
                        stopGeneration,
                    )
                    previousStopTimedOut = stopGeneration == startGeneration &&
                        (stopRequested || (activeClient == null && probeClient != null))
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { client.delete() }
            return "OpenVPN startup was interrupted"
        }

        if (!acquired) {
            runCatching { client.delete() }
            return when {
                canceledDuringWait -> "OpenVPN startup stopped"
                previousStopTimedOut ->
                "The previous OpenVPN connection did not stop in time"
                else -> "An OpenVPN connection is already running"
            }
        }

        var failure = ""
        val nativeConfig = ClientAPI_Config()
        try {
            nativeConfig.setContent(profile)
            nativeConfig.setGuiVersion("PingNG Android")
            nativeConfig.setPrivateKeyPassword(privateKeyPassphrase)
            // Android does not provide OpenVPN DCO. Allow explicitly requested
            // legacy/non-preferred profile ciphers through the OpenSSL engine.
            nativeConfig.setDco(false)
            // Android 13 adds native excluded routes. Older releases need OpenVPN 3
            // to split include routes around exclusions before handing them to Builder.
            nativeConfig.setEnableRouteEmulation(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
            )
            nativeConfig.setEnableNonPreferredDCAlgorithms(
                OpenVpnProfileCodec.explicitDataCipherRequested(profile),
            )
            nativeConfig.setEnableLegacyAlgorithms(
                OpenVpnProfileCodec.requestsLegacyAlgorithms(profile),
            )
            if (OpenVpnProfileCodec.requestsCompression(profile)) {
                // Match ics-openvpn's OpenVPN 3 client behavior for legacy compressed servers.
                nativeConfig.setCompressionMode("asym")
            }

            val evaluation = client.eval_config(nativeConfig)
            try {
                if (evaluation.getError()) {
                    failure = evaluation.getMessage().ifBlank { "OpenVPN rejected the configuration" }
                }
            } finally {
                evaluation.delete()
            }

            if (failure.isBlank() && (username.isNotBlank() || password.isNotBlank())) {
                val credentials = ClientAPI_ProvideCreds()
                try {
                    credentials.setUsername(username)
                    credentials.setPassword(password)
                    val credentialStatus = client.provide_creds(credentials)
                    try {
                        if (credentialStatus.getError()) {
                            failure = credentialStatus.getMessage()
                                .ifBlank { credentialStatus.getStatus() }
                        }
                    } finally {
                        credentialStatus.delete()
                    }
                } finally {
                    credentials.setUsername("")
                    credentials.setPassword("")
                    credentials.delete()
                }
            }

            val shouldConnect = stateLock.withLock {
                activeClient === client && !stopRequested &&
                    OpenVpnLifecyclePolicy.mayContinueStartup(
                        service.isStartupCancelled(),
                        service.isOpenVpnStopRequested(),
                    )
            }
            if (failure.isBlank() && !shouldConnect) {
                failure = "OpenVPN startup stopped"
            }

            if (failure.isBlank()) {
                val connectStatus = client.connect()
                try {
                    if (connectStatus.getError()) {
                        failure = connectStatus.getMessage()
                            .ifBlank { connectStatus.getStatus() }
                    }
                } finally {
                    connectStatus.delete()
                }
            }
        } catch (error: Throwable) {
            failure = error.message?.takeIf(String::isNotBlank)
                ?: error.javaClass.simpleName
        } finally {
            runCatching {
                nativeConfig.setContent("")
                nativeConfig.setPrivateKeyPassword("")
                nativeConfig.delete()
            }
        }

        var safeFailure = failure
        try {
            runCatching { client.reportTransportStats("connect_return") }
            safeFailure = runCatching { client.redact(failure).trim() }.getOrDefault(failure)
        } finally {
            try {
                stateLock.withLock {
                    if (activeClient === client) {
                        activeClient = null
                        stopRequested = false
                        stopped.signalAll()
                    }
                }
            } finally {
                runCatching { client.delete() }
            }
        }
        return safeFailure
    }

    @Keep
    fun nativeStop() {
        val clients = stateLock.withLock {
            stopGeneration++
            if (activeClient != null) stopRequested = true
            listOfNotNull(activeClient, probeClient).distinct()
        }
        clients.forEach { client -> runCatching { client.stop() } }
    }

    /** Runs OpenVPN 3 with the imported profile and measures its actual control-channel handshake. */
    fun probeHandshakeDelayMillis(
        profileGuid: String,
        profile: String,
        username: String,
        password: String,
        privateKeyPassphrase: String,
        socketProtect: (Int) -> Boolean,
    ): Long {
        val client = AndroidOpenVPNClient(
            service = null,
            username = username,
            password = password,
            privateKeyPassphrase = privateKeyPassphrase,
            probeOnly = true,
            socketProtect = socketProtect,
        )
        var probeGeneration = 0L
        val acquired = stateLock.withLock {
            if (probeClient != null || stopRequested) {
                false
            } else {
                probeGeneration = stopGeneration
                probeClient = client
                true
            }
        }
        if (!acquired) {
            runCatching { client.delete() }
            PingNgDiagnostics.record("OpenVPN real-delay probe unavailable profile=${profileGuid.take(8)}: client is stopping")
            return -1L
        }

        var nativeConfig: ClientAPI_Config? = null
        var failure = ""
        var delay = -1L
        var safeFailure = ""
        var stopTimer: java.util.concurrent.ScheduledExecutorService? = null
        var stopTask: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            val config = ClientAPI_Config()
            nativeConfig = config
            config.setContent(profile)
            config.setGuiVersion("PingNG Android real-delay probe")
            config.setPrivateKeyPassword(privateKeyPassphrase)
            config.setDco(false)
            config.setEnableRouteEmulation(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            config.setEnableNonPreferredDCAlgorithms(OpenVpnProfileCodec.explicitDataCipherRequested(profile))
            config.setEnableLegacyAlgorithms(OpenVpnProfileCodec.requestsLegacyAlgorithms(profile))
            if (OpenVpnProfileCodec.requestsCompression(profile)) {
                config.setCompressionMode("asym")
            }

            val evaluation = client.eval_config(config)
            try {
                if (evaluation.getError()) {
                    failure = evaluation.getMessage().ifBlank { "OpenVPN rejected the configuration" }
                }
            } finally {
                evaluation.delete()
            }

            if (failure.isBlank() && (username.isNotBlank() || password.isNotBlank())) {
                val credentials = ClientAPI_ProvideCreds()
                try {
                    credentials.setUsername(username)
                    credentials.setPassword(password)
                    val credentialStatus = client.provide_creds(credentials)
                    try {
                        if (credentialStatus.getError()) {
                            failure = credentialStatus.getMessage().ifBlank { credentialStatus.getStatus() }
                        }
                    } finally {
                        credentialStatus.delete()
                    }
                } finally {
                    credentials.setUsername("")
                    credentials.setPassword("")
                    credentials.delete()
                }
            }

            val shouldConnect = stateLock.withLock {
                probeClient === client && !stopRequested &&
                    !OpenVpnLifecyclePolicy.wasNativeStartCanceled(probeGeneration, stopGeneration)
            }
            if (failure.isBlank() && shouldConnect) {
                client.startProbeTimer()
                val timer = Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "PingNG-OpenVPN-Probe-Timeout").apply { isDaemon = true }
                }
                stopTimer = timer
                stopTask = timer.schedule({
                    runCatching { client.stop() }
                    Unit
                }, 10, TimeUnit.SECONDS)
                val connectStatus = client.connect()
                try {
                    if (connectStatus.getError()) {
                        failure = connectStatus.getMessage().ifBlank { connectStatus.getStatus() }
                    }
                } finally {
                    connectStatus.delete()
                }
            } else if (failure.isBlank()) {
                failure = "OpenVPN real-delay probe stopped"
            }
        } catch (error: Throwable) {
            failure = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        } finally {
            stopTask?.cancel(false)
            stopTimer?.shutdownNow()
            delay = client.probeHandshakeDelayMillis
            safeFailure = runCatching { client.redact(failure).trim() }.getOrDefault("")
            nativeConfig?.let { config ->
                runCatching {
                    config.setContent("")
                    config.setPrivateKeyPassword("")
                    config.delete()
                }
            }
            stateLock.withLock {
                if (probeClient === client) {
                    probeClient = null
                    stopped.signalAll()
                }
            }
            runCatching { client.delete() }
        }

        if (delay < 0L) {
            PingNgDiagnostics.record(
                "OpenVPN real-delay handshake failed profile=${profileGuid.take(8)}" +
                    safeFailure.takeIf(String::isNotBlank)?.let { ": ${it.take(240)}" }.orEmpty(),
            )
        }
        return delay
    }

    @Keep
    private class AndroidOpenVPNClient(
        private val service: CoreVpnService?,
        username: String,
        password: String,
        privateKeyPassphrase: String,
        private val probeOnly: Boolean = false,
        private val socketProtect: (Int) -> Boolean = { socket -> service?.protectOpenVpnSocket(socket) == true },
    ) : ClientAPI_OpenVPNClient() {
        private val secrets = listOf(username, password, privateKeyPassphrase)
            .filter(String::isNotEmpty)
            .distinct()
            .sortedByDescending(String::length)

        private var tunnel = TunnelPlan()
        @Volatile
        var probeHandshakeDelayMillis: Long = -1L
            private set
        private var probeStartedAtNanos = 0L

        fun startProbeTimer() {
            probeStartedAtNanos = System.nanoTime()
        }

        override fun socket_protect(socket: Int, remote: String, ipv6: Boolean): Boolean =
            socketProtect(socket)

        override fun tun_builder_new(): Boolean {
            tunnel = TunnelPlan()
            return service?.let {
                OpenVpnLifecyclePolicy.mayContinueStartup(
                    it.isStartupCancelled(),
                    it.isOpenVpnStopRequested(),
                )
            } != false
        }

        override fun tun_builder_set_layer(layer: Int): Boolean = layer == 3

        override fun tun_builder_set_remote_address(address: String, ipv6: Boolean): Boolean = true

        override fun tun_builder_add_address(
            address: String,
            prefixLength: Int,
            gateway: String,
            ipv6: Boolean,
            net30: Boolean,
        ): Boolean {
            tunnel.addresses.add(address + "/" + prefixLength)
            return true
        }

        override fun tun_builder_set_route_metric_default(metric: Int): Boolean = true

        override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
            // OpenVPN 3 already emitted split positive routes for excluded networks.
            if ((flags and (1L shl 16)) != 0L) return true
            if (ipv4) tunnel.routes.add("0.0.0.0/0")
            if (ipv6) tunnel.routes.add("::/0")
            return true
        }

        override fun tun_builder_add_route(
            address: String,
            prefixLength: Int,
            metric: Int,
            ipv6: Boolean,
        ): Boolean {
            tunnel.routes.add(address + "/" + prefixLength)
            return true
        }

        override fun tun_builder_exclude_route(
            address: String,
            prefixLength: Int,
            metric: Int,
            ipv6: Boolean,
        ): Boolean {
            tunnel.excludedRoutes.add(address + "/" + prefixLength)
            return true
        }

        override fun tun_builder_set_dns_options(options: DnsOptions): Boolean {
            val servers = options.getServers()
            try {
                // SWIG exposes the map's Java entrySet() publicly. Its native
                // begin/end iterator helpers are private in this API build.
                for (rawEntry in servers.entries) {
                    val entry = rawEntry as? Map.Entry<*, *> ?: continue
                    val server = entry.value as? DnsServer ?: continue
                    val addresses = server.getAddresses()
                    val domains = server.getDomains()
                    try {
                        val dnssec = server.dnssec_string(server.getDnssec())
                        val transport = server.transport_string(server.getTransport())
                        val supportsAndroidDns = !dnssec.equals("yes", ignoreCase = true) &&
                            (transport.equals("plain", ignoreCase = true) ||
                                transport.equals("unset", ignoreCase = true))
                        for (index in 0 until addresses.size) {
                            val address = addresses.get(index)
                            try {
                                val port = address.getPort()
                                if (supportsAndroidDns && (port == 0L || port == 53L)) {
                                    tunnel.dnsServers.add(address.getAddress())
                                }
                            } finally {
                                address.delete()
                            }
                        }
                        for (index in 0 until domains.size) {
                            val domain = domains.get(index)
                            try {
                                tunnel.searchDomains.add(domain.getDomain())
                            } finally {
                                domain.delete()
                            }
                        }
                    } finally {
                        addresses.delete()
                        domains.delete()
                        server.delete()
                    }
                }
                val searchDomains = options.getSearch_domains()
                try {
                    for (index in 0 until searchDomains.size) {
                        val domain = searchDomains.get(index)
                        try {
                            tunnel.searchDomains.add(domain.getDomain())
                        } finally {
                            domain.delete()
                        }
                    }
                } finally {
                    searchDomains.delete()
                }
            } finally {
                servers.delete()
            }
            return true
        }

        override fun tun_builder_set_mtu(mtu: Int): Boolean {
            tunnel.mtu = mtu
            return true
        }

        override fun tun_builder_set_session_name(name: String): Boolean {
            tunnel.sessionName = name
            return true
        }

        override fun tun_builder_add_proxy_bypass(host: String): Boolean = true

        override fun tun_builder_set_proxy_auto_config_url(url: String): Boolean = true

        override fun tun_builder_set_proxy_http(host: String, port: Int): Boolean = true

        override fun tun_builder_set_proxy_https(host: String, port: Int): Boolean = true

        override fun tun_builder_add_wins_server(address: String): Boolean = true

        override fun tun_builder_set_allow_family(family: Int, allow: Boolean): Boolean {
            if (allow) {
                tunnel.allowedFamilies.add(family)
            } else {
                tunnel.allowedFamilies.remove(family)
            }
            return true
        }

        override fun tun_builder_set_allow_local_dns(allow: Boolean): Boolean = true

        override fun tun_builder_establish(): Int {
            if (probeOnly) {
                probeHandshakeDelayMillis = ((System.nanoTime() - probeStartedAtNanos) / 1_000_000L)
                    .coerceAtLeast(0L)
                return -1
            }
            return service?.establishOpenVpnTunnel(
                tunnel.addresses.toTypedArray(),
                tunnel.routes.toTypedArray(),
                tunnel.excludedRoutes.toTypedArray(),
                tunnel.dnsServers.toTypedArray(),
                tunnel.searchDomains.toTypedArray(),
                tunnel.allowedFamilies.toIntArray(),
                tunnel.mtu,
                tunnel.sessionName,
            ) ?: -1
        }

        override fun tun_builder_persist(): Boolean = false

        override fun tun_builder_teardown(disconnect: Boolean) = Unit

        override fun pause_on_connection_timeout(): Boolean = false

        override fun event(event: ClientAPI_Event) {
            val name = event.getName()
            if (!probeOnly && (name == "RECONNECTING" || name == "CONNECTED")) {
                reportTransportStats(name)
            }
            val safeInfo = redact(event.getInfo())
            if (probeOnly) {
                if (event.getError() && probeHandshakeDelayMillis < 0L) {
                    PingNgDiagnostics.record(
                        "OpenVPN real-delay handshake event=$name: ${safeInfo.ifBlank { "no details" }.take(240)}",
                    )
                }
            } else {
                service?.onOpenVpnEvent(name, event.getError(), event.getFatal(), safeInfo)
            }
        }

        override fun log(info: ClientAPI_LogInfo) {
            if (!probeOnly) service?.onOpenVpnLog(redact(info.getText()))
        }

        override fun external_pki_cert_request(request: ClientAPI_ExternalPKICertRequest) {
            request.setError(true)
            request.setErrorText("External PKI certificates are not available in this client")
        }

        override fun external_pki_sign_request(request: ClientAPI_ExternalPKISignRequest) {
            request.setError(true)
            request.setErrorText("External PKI signing is not available in this client")
        }

        override fun remote_override_enabled(): Boolean = false

        override fun remote_override(remoteOverride: ClientAPI_RemoteOverride) = Unit

        override fun clock_tick() = Unit

        fun redact(text: String): String {
            var safe = text
            for (secret in secrets) safe = safe.replace(secret, "[REDACTED]")
            return safe
        }

        fun reportTransportStats(eventName: String) {
            val stats = transport_stats()
            try {
                service?.onOpenVpnLog(
                    "PingNG OpenVPN transport stats event=" + eventName +
                        " packets_in=" + stats.getPacketsIn() +
                        " bytes_in=" + stats.getBytesIn() +
                        " packets_out=" + stats.getPacketsOut() +
                        " bytes_out=" + stats.getBytesOut() +
                        " last_packet_received_1024s=" + stats.getLastPacketReceived(),
                )
            } finally {
                stats.delete()
            }
        }
    }

    private class TunnelPlan {
        val addresses = linkedSetOf<String>()
        val routes = linkedSetOf<String>()
        val excludedRoutes = linkedSetOf<String>()
        val dnsServers = linkedSetOf<String>()
        val searchDomains = linkedSetOf<String>()
        val allowedFamilies = linkedSetOf<Int>()
        var mtu: Int = 0
        var sessionName: String = ""
    }
}
