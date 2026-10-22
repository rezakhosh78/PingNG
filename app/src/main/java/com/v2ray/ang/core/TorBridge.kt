package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.TorStatus
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import org.torproject.jni.TorService as EmbeddedTorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Runs Tor as a local SOCKS hop and keeps its upstream on the selected profile. */
object TorBridge {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    private const val TAG = "PingNG-Tor"
    private const val UPSTREAM_TAG = "pingng-tor-upstream"
    private const val SNOWFLAKE_UPSTREAM_TAG = "pingng-snowflake-upstream"
    private const val SNOWFLAKE_UDP_TAG = "pingng-snowflake-direct-udp"
    private const val EGRESS_TAG = "pingng-tor-egress"
    private const val BLOCK_UDP_TAG = "pingng-tor-block-udp"
    private const val BLOCK_IPV6_TAG = "pingng-tor-block-ipv6"
    private const val DNS_MODULE_TAG = "pingng-tor-dns-module"
    private const val LEGACY_DNS_LOCAL_TAG = "pingng-tor-dns-local"
    private const val TOR_CIRCUIT_STARTUP_TIMEOUT_MS = 5 * 60 * 1_000L
    private const val STATUS_ACTION = "org.torproject.android.intent.action.STATUS"
    private const val ERROR_ACTION = "org.torproject.android.intent.action.ERROR"
    private const val STATUS_EXTRA = "org.torproject.android.intent.extra.STATUS"
    private const val SERVICE_PACKAGE_EXTRA = "org.torproject.android.intent.extra.SERVICE_PACKAGE_NAME"
    private const val SOCKS_PORT_FALLBACK = 19050

    @Volatile private var upstreamHttpPort = 0
    @Volatile private var upstreamSocksPort = 0
    @Volatile private var plannedSocksPort = 0
    @Volatile private var socksPort = 0
    @Volatile private var connected = false
    @Volatile private var activeGuid: String? = null
    @Volatile private var activeSnowflakeRunId: String? = null
    @Volatile private var statusContext: Context? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var snowflakeReceiverRegistered = false
    @Volatile private var torServiceStarted = false
    @Volatile private var snowflakeServiceStarted = false
    @Volatile private var snowflakeReady = false
    @Volatile private var activeProfile: ProfileItem? = null
    @Volatile private var activeUpstreamPort = 0
    @Volatile private var activeUpstreamSocksPort = 0
    private val starting = AtomicBoolean(false)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    @Volatile private var circuitWatchdog: Thread? = null
    @Volatile private var snowflakeStartupWatchdog: Thread? = null
    @Volatile private var bootstrapLog: File? = null

    private val snowflakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val app = context ?: return
            val receivedIntent = intent ?: return
            val runId = receivedIntent.getStringExtra(TorSnowflakeTransportService.EXTRA_RUN_ID) ?: return
            if (runId != activeSnowflakeRunId || !starting.get()) return
            when (receivedIntent.action) {
                TorSnowflakeTransportService.ACTION_READY -> {
                    val address = receivedIntent.getStringExtra(TorSnowflakeTransportService.EXTRA_ADDRESS).orEmpty()
                    if (!isLoopbackProxyAddress(address)) {
                        activeGuid?.let { failTorStartup(app, it, "Snowflake returned an invalid local proxy address") }
                        return
                    }
                    snowflakeReady = true
                    snowflakeStartupWatchdog?.interrupt()
                    snowflakeStartupWatchdog = null
                    val profile = activeProfile ?: return
                    val guid = activeGuid ?: return
                    PingNgDiagnostics.record("Snowflake local proxy is listening at $address; starting Tor with Snowflake only")
                    launchTor(app, guid, profile, activeUpstreamPort, activeUpstreamSocksPort, address)
                }
                TorSnowflakeTransportService.ACTION_FAILED -> {
                    val error = receivedIntent.getStringExtra(TorSnowflakeTransportService.EXTRA_ERROR)
                        .orEmpty().ifBlank { "Snowflake transport failed to start" }
                    activeGuid?.let { failTorStartup(app, it, error) }
                }
                TorSnowflakeTransportService.ACTION_EVENT -> {
                    val event = receivedIntent.getStringExtra(TorSnowflakeTransportService.EXTRA_EVENT).orEmpty()
                    val detail = receivedIntent.getStringExtra(TorSnowflakeTransportService.EXTRA_DETAIL).orEmpty()
                    PingNgDiagnostics.record(
                        "Snowflake transport event: $event" + detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
                    )
                    if (event == "error" && !connected) {
                        // Snowflake reports DataChannel.OnOpen timeouts for individual
                        // temporary proxies. Keep the transport alive so its client can
                        // rendezvous with another proxy instead of aborting Tor startup.
                        PingNgDiagnostics.record(
                            "Snowflake proxy attempt failed; keeping the transport active for retry",
                        )
                    } else if (event == "stopped" && !connected) {
                        val guid = activeGuid ?: return
                        failTorStartup(app, guid, detail.ifBlank { "Snowflake transport $event" })
                    }
                }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val receiverContext = context ?: return
            val receivedIntent = intent ?: return
            if (receivedIntent.getStringExtra(SERVICE_PACKAGE_EXTRA) != receiverContext.packageName) return
            val guid = activeGuid ?: return
            when (receivedIntent.action) {
                STATUS_ACTION -> when (receivedIntent.getStringExtra(STATUS_EXTRA)) {
                    EmbeddedTorService.STATUS_ON -> {
                        // A running Tor service is not proof that it has built
                        // an exit circuit. The watchdog marks it connected
                        // only after a successful SOCKS CONNECT probe.
                        PingNgDiagnostics.record("Tor service is running; verifying its first circuit")
                    }
                    EmbeddedTorService.STATUS_OFF,
                    EmbeddedTorService.STATUS_STOPPING -> {
                        val status = receivedIntent.getStringExtra(STATUS_EXTRA)
                        PingNgDiagnostics.record("Tor service status: ${status.orEmpty()}")
                        val wasConnected = connected
                        connected = false
                        // Stopping is disconnected; animate only after a new
                        // STARTING event confirms Tor is bootstrapping again.
                        publishConnectionState(ConnectionState.DISCONNECTED)
                        socksPort = 0
                        starting.set(false)
                        if (status == EmbeddedTorService.STATUS_OFF) {
                            torServiceStarted = false
                            stopSnowflakeTransport(receiverContext)
                            if (!wasConnected && activeGuid == guid) {
                                CoreServiceManager.onTorFailed("Tor stopped before establishing a circuit")
                            }
                        }
                        if (wasConnected && activeGuid == guid) CoreServiceManager.onTorDisconnected()
                    }
                    EmbeddedTorService.STATUS_STARTING -> {
                        starting.set(true)
                        publishConnectionState(ConnectionState.CONNECTING)
                        PingNgDiagnostics.record("Tor status: bootstrapping")
                    }
                }
                ERROR_ACTION -> {
                    val error = receivedIntent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().ifBlank { "Tor failed to start" }
                    val wasConnected = connected
                    connected = false
                    publishConnectionState(ConnectionState.DISCONNECTED)
                    socksPort = 0
                    torServiceStarted = false
                    starting.set(false)
                    circuitWatchdog?.interrupt()
                    circuitWatchdog = null
                    stopSnowflakeTransport(receiverContext)
                    PingNgDiagnostics.record("Tor runtime error: $error")
                    if (wasConnected) CoreServiceManager.onTorDisconnected()
                    else CoreServiceManager.onTorFailed(error)
                }
            }
        }
    }

    fun isRunning(): Boolean = torServiceStarted
    fun isConnected(): Boolean = connected
    fun isConnecting(): Boolean = starting.get() && activeGuid != null && !connected
    fun activeSocksPort(): Int = socksPort.takeIf { connected && it in 1..65535 } ?: 0

    @Synchronized
    private fun markCircuitReady(guid: String, source: String) {
        if (activeGuid != guid || connected) return
        connected = true
        publishConnectionState(ConnectionState.CONNECTED)
        socksPort = plannedSocksPort
        circuitWatchdog?.interrupt()
        circuitWatchdog = null
        PingNgDiagnostics.record("Tor circuit established via $source; local TCP egress is ready")
        CoreServiceManager.onTorConnected()
    }

    /** Tor runs in a separate Android process, so publish state for the main UI over a broadcast. */
    private fun publishConnectionState(state: ConnectionState) {
        _connectionState.value = state
        val guid = activeGuid ?: return
        val context = statusContext ?: return
        val uiState = when (state) {
            ConnectionState.CONNECTING -> TorStatus.CONNECTING
            ConnectionState.CONNECTED -> TorStatus.CONNECTED
            ConnectionState.DISCONNECTED -> TorStatus.STOPPED
        }
        runCatching {
            MessageHelper.sendMsg2UI(context, AppConfig.MSG_TOR_STATUS, TorStatus(guid, uiState))
        }.onFailure { error ->
            PingNgDiagnostics.record("Tor status update could not reach the main UI", error)
        }
    }

    /** A successful SOCKS CONNECT requires a working Tor exit circuit. */
    private fun probeCircuit(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_500)
            socket.soTimeout = 6_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            if (input.read() != 5 || input.read() != 0) return@runCatching false
            output.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 1, -69)) // 1.1.1.1:443
            output.flush()
            input.read() == 5 && input.read() == 0
        }
    }.getOrDefault(false)

    fun usesStableEgress(profile: ProfileItem?): Boolean = profile?.let {
        it.torEnabled &&
            (it.configType == EConfigType.AMNEZIAWG || SettingsManager.isUsingHevTun()) &&
            plannedSocksPort in 1..65535
    } == true

    @Synchronized
    fun prepareAmneziaEgress(): Int {
        ensurePorts()
        return plannedSocksPort
    }

    @Synchronized
    fun setAmneziaUpstream(port: Int) {
        require(port in 1..65535)
        upstreamHttpPort = port
    }

    /** Routes TCP through Tor, resolves DNS over Tor, and prevents QUIC from stalling TCP fallback. */
    @Synchronized
    fun prepareCoreConfig(profile: ProfileItem, raw: String): String {
        if (!profile.torEnabled) return raw
        ensurePorts()

        val root = JsonParser.parseString(raw).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray().also { root.add("inbounds", it) }
        val upstream = inbounds.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == UPSTREAM_TAG
        }?.asJsonObject
        upstream?.let { inbounds.remove(it) }
        inbounds.add(JsonObject().apply {
            addProperty("tag", UPSTREAM_TAG)
            addProperty("listen", "127.0.0.1")
            addProperty("port", upstreamHttpPort)
            addProperty("protocol", "http")
            add("settings", JsonObject().apply { addProperty("timeout", 0) })
        })
        val snowflakeUpstream = inbounds.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == SNOWFLAKE_UPSTREAM_TAG
        }?.asJsonObject
        snowflakeUpstream?.let { inbounds.remove(it) }
        inbounds.add(JsonObject().apply {
            addProperty("tag", SNOWFLAKE_UPSTREAM_TAG)
            addProperty("listen", "127.0.0.1")
            addProperty("port", upstreamSocksPort)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
                addProperty("userLevel", 8)
            })
        })

        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray().also { root.add("outbounds", it) }
        removeTagged(outbounds, EGRESS_TAG)
        removeTagged(outbounds, SNOWFLAKE_UDP_TAG)
        removeTagged(outbounds, BLOCK_UDP_TAG)
        removeTagged(outbounds, BLOCK_IPV6_TAG)
        // Remove the former direct-DNSPort outbound when updating a running
        // profile from an older app version.
        removeTagged(outbounds, LEGACY_DNS_LOCAL_TAG)
        outbounds.add(JsonObject().apply {
            addProperty("tag", EGRESS_TAG)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("version", "5")
                addProperty("udp", false)
                add("servers", JsonArray().also { servers ->
                    servers.add(JsonObject().apply {
                        addProperty("address", "127.0.0.1")
                        addProperty("port", plannedSocksPort)
                    })
                })
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "tcp")
                add("sockopt", JsonObject().apply { addProperty("domainStrategy", "UseIPv4") })
            })
        })
        // Snowflake broker HTTPS uses the selected profile, while STUN and
        // WebRTC peer datagrams need a native UDP socket for ICE/NAT traversal.
        // Sending those datagrams into a TCP-only VLESS/WS hop causes repeated
        // peer timeouts before Tor can build its first circuit.
        outbounds.add(JsonObject().apply {
            addProperty("tag", SNOWFLAKE_UDP_TAG)
            addProperty("protocol", "freedom")
            add("settings", JsonObject())
            add("streamSettings", JsonObject().apply {
                add("sockopt", JsonObject().apply { addProperty("domainStrategy", "UseIPv4") })
            })
        })
        outbounds.add(JsonObject().apply {
            addProperty("tag", BLOCK_UDP_TAG)
            addProperty("protocol", "blackhole")
            add("settings", JsonObject())
        })
        outbounds.add(JsonObject().apply {
            addProperty("tag", BLOCK_IPV6_TAG)
            addProperty("protocol", "blackhole")
            add("settings", JsonObject())
        })
        if (outbounds.none { it.isJsonObject && it.asJsonObject.get("tag")?.asString == "dns-out" }) {
            outbounds.add(JsonObject().apply {
                addProperty("tag", "dns-out")
                addProperty("protocol", "dns")
            })
        }

        val dns = root.getAsJsonObject("dns") ?: JsonObject().also { root.add("dns", it) }
        dns.addProperty("tag", DNS_MODULE_TAG)
        // Resolve through encrypted DNS over TCP, routed through Tor's SOCKS
        // egress. The previous local Tor DNSPort path was receiving requests
        // but domains in the device log did not lead to website TCP sessions.
        dns.addProperty("queryStrategy", "UseIPv4")
        // Replace the selected profile's DNS list with two encrypted resolvers.
        // Xray tries the second only if the first fails; both use Tor egress.
        dns.addProperty("disableFallback", false)
        // Pin DoH endpoint addresses to avoid a DNS bootstrap loop. HTTPS still
        // uses each hostname for TLS/SNI and is sent over Tor.
        val hosts = dns.get("hosts")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { dns.add("hosts", it) }
        hosts.add("cloudflare-dns.com", JsonArray().also {
            it.add("1.1.1.1")
            it.add("1.0.0.1")
        })
        hosts.add("dns.google", JsonArray().also {
            it.add("8.8.8.8")
            it.add("8.8.4.4")
        })
        dns.add("servers", JsonArray().also {
            it.add("https://cloudflare-dns.com/dns-query")
            it.add("https://dns.google/dns-query")
        })

        val routing = root.getAsJsonObject("routing") ?: JsonObject().also {
            it.addProperty("domainStrategy", "AsIs")
            it.add("rules", JsonArray())
            root.add("routing", it)
        }
        val userInboundTags = inbounds.mapNotNull { inbound ->
            inbound.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeUnless {
                    it == UPSTREAM_TAG || it == SNOWFLAKE_UPSTREAM_TAG ||
                        it == WarpMasqueFinalMaskConfig.INBOUND_TAG
                }
        }.distinct().ifEmpty { listOf("tun") }
        val rules = routing.getAsJsonArray("rules") ?: JsonArray()
        val upstreamOutbound = selectUpstreamOutbound(profile, outbounds)
        val snowflakeBootstrapIps = addUpstreamHostMappings(dns, profile, outbounds, upstreamOutbound)
        val torRules = arrayListOf(
            rule(inboundTags = listOf(UPSTREAM_TAG), outboundTag = upstreamOutbound),
            rule(inboundTags = listOf(SNOWFLAKE_UPSTREAM_TAG), network = "udp", outboundTag = SNOWFLAKE_UDP_TAG),
            rule(inboundTags = listOf(SNOWFLAKE_UPSTREAM_TAG), network = "tcp", outboundTag = upstreamOutbound),
            rule(inboundTags = listOf(DNS_MODULE_TAG), outboundTag = EGRESS_TAG),
            rule(inboundTags = userInboundTags, network = "udp", port = "53", outboundTag = "dns-out"),
            // Tor only transports TCP. Drop QUIC early so browsers immediately
            // fall back to TCP through Tor instead of waiting on UDP/443 retries.
            rule(inboundTags = userInboundTags, network = "udp", port = "443", outboundTag = BLOCK_UDP_TAG),
            // Some clients still use cached or hard-coded AAAA answers. Tor
            // exits often cannot reach these targets, so fail IPv6 fast and
            // let dual-stack clients retry the IPv4 answer through Tor.
            rule(inboundTags = userInboundTags, network = "tcp", ipAddresses = listOf("::/0"), outboundTag = BLOCK_IPV6_TAG),
        )
        if (snowflakeBootstrapIps.isNotEmpty()) {
            torRules += rule(
                inboundTags = userInboundTags,
                ipAddresses = snowflakeBootstrapIps,
                outboundTag = upstreamOutbound,
            )
        }
        torRules += rule(inboundTags = userInboundTags, network = "udp", outboundTag = upstreamOutbound)
        torRules += rule(inboundTags = userInboundTags, network = "tcp", outboundTag = EGRESS_TAG)
        val keptRules = rules.filterNot { existing -> isPreviousTorRule(existing, userInboundTags, upstreamOutbound) }
        val finalMaskRules = keptRules.filter { existing ->
            existing.isJsonObject && existing.asJsonObject.getAsJsonArray("inboundTag")
                ?.any { it.isJsonPrimitive && it.asString == WarpMasqueFinalMaskConfig.INBOUND_TAG } == true
        }
        val otherRules = keptRules - finalMaskRules.toSet()
        routing.add("rules", JsonArray().apply {
            finalMaskRules.forEach { add(it) }
            torRules.forEach { add(it) }
            otherRules.forEach { add(it) }
        })
        PingNgDiagnostics.record(
            "Tor route prepared: Snowflake broker TCP -> $upstreamOutbound; Snowflake STUN/WebRTC UDP -> direct (IPv4 preferred); bootstrap -> $upstreamOutbound; app IPv4 TCP -> Tor; IPv6 TCP blocked for IPv4 fallback; app DNS -> Cloudflare/Google DoH over Tor SOCKS (IPv4, Tor-only fallback); QUIC UDP/443 blocked for TCP fallback; other UDP -> $upstreamOutbound",
        )
        return root.toString()
    }

    /** Starts Tor only after its configured Xray/AWG upstream is accepting connections. */
    @Synchronized
    fun startAfterCoreConnected(service: Service, guid: String, profile: ProfileItem) {
        if (!profile.torEnabled || !starting.compareAndSet(false, true)) return
        ensurePorts()
        activeGuid = guid
        activeSnowflakeRunId = UUID.randomUUID().toString()
        statusContext = service.applicationContext
        activeProfile = profile
        activeUpstreamPort = upstreamHttpPort
        activeUpstreamSocksPort = upstreamSocksPort
        connected = false
        // The selected base profile must connect first. Keep the Tor badge
        // red and steady while only the upstream tunnel is being checked.
        publishConnectionState(ConnectionState.DISCONNECTED)
        socksPort = 0
        val app = service.applicationContext
        Thread({
            try {
                val port = upstreamHttpPort
                require(port in 1..65535) { "Tor upstream is unavailable" }
                PingNgDiagnostics.record("Waiting for the selected configuration to establish its upstream tunnel")
                val deadline = System.nanoTime() + 90_000_000_000L
                var ready = false
                while (System.nanoTime() < deadline && activeGuid == guid && starting.get()) {
                    if (probeUpstream(port)) { ready = true; break }
                    Thread.sleep(1_500L)
                }
                if (activeGuid != guid || !starting.get()) return@Thread
                if (!ready) {
                    failTorStartup(app, guid, "The selected configuration did not connect before Tor startup")
                    return@Thread
                }
                PingNgDiagnostics.record("Selected configuration connected; starting Tor through its upstream")
                publishConnectionState(ConnectionState.CONNECTING)
                val runId = activeSnowflakeRunId ?: return@Thread
                startSnowflakeTransport(app, guid, runId)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (activeGuid == guid && starting.get()) {
                    failTorStartup(app, guid, error.message.orEmpty().ifBlank { "Tor upstream check failed" })
                }
            }
        }, "pingng-tor-upstream-check").apply { isDaemon = true; start() }
    }

    /** HTTP CONNECT succeeds only when the selected upstream can carry traffic. */
    private fun probeUpstream(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            val firstLine = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
            firstLine?.startsWith("HTTP/1.1 200") == true || firstLine?.startsWith("HTTP/1.0 200") == true
        }
    }.getOrDefault(false)

    @Synchronized
    private fun launchTor(
        app: Context,
        guid: String,
        profile: ProfileItem,
        upstreamPort: Int,
        snowflakeUpstreamPort: Int,
        snowflakeAddress: String,
    ) {
        if (activeGuid != guid || !starting.get()) return
        registerReceiver(app)
        try {
            val logFile = File(app.filesDir, "pingng-tor-bootstrap.log")
            logFile.writeText("")
            bootstrapLog = logFile
            val lines = buildList {
                add("SocksPort 127.0.0.1:$plannedSocksPort")
                add("ClientOnly 1")
                add("UseBridges 1")
                add("ClientTransportPlugin snowflake socks5 $snowflakeAddress")
                val bridge = buildString {
                    append("Bridge snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72")
                    if (snowflakeUpstreamPort in 1..65535) {
                        // Snowflake consumes this SOCKS argument for its broker HTTPS
                        // requests. Tor's HTTPProxy only affects Tor itself, not IPtProxy.
                        append(" proxy=socks5://127.0.0.1:")
                        append(snowflakeUpstreamPort)
                    }
                }
                add(bridge)
                // Retain directory information and guards between connections;
                // otherwise each start can spend minutes downloading descriptors.
                add("Log notice file ${logFile.absolutePath}")
                profile.psiphonRegion?.trim()?.uppercase()
                    ?.takeIf { it.matches(Regex("[A-Z]{2}")) }
                    ?.let { country ->
                        add("ExitNodes {${country.lowercase()}}")
                        add("StrictNodes 0")
                    }
                if (upstreamPort in 1..65535) add("HTTPProxy 127.0.0.1:$upstreamPort")
            }
            val torrc = EmbeddedTorService.getTorrc(app)
            torrc.parentFile?.mkdirs()
            torrc.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            torServiceStarted = true
            app.startService(Intent(app, EmbeddedTorService::class.java).setAction(EmbeddedTorService.ACTION_START))
            PingNgDiagnostics.record(
                "Tor starting with Snowflake only; Tor HTTP upstream 127.0.0.1:$upstreamPort; " +
                    "Snowflake SOCKS5 upstream 127.0.0.1:$snowflakeUpstreamPort",
            )
            circuitWatchdog = Thread({
                try {
                    val startupDeadlineNanos = System.nanoTime() +
                        TOR_CIRCUIT_STARTUP_TIMEOUT_MS * 1_000_000L
                    var previousProgress = ""
                    var lastProgressAt = System.nanoTime()
                    var slowBootstrapReported = false
                    while (activeGuid == guid && !connected && torServiceStarted) {
                        if (System.nanoTime() >= startupDeadlineNanos) {
                            failTorStartup(
                                app,
                                guid,
                                "Tor did not establish a circuit within ${TOR_CIRCUIT_STARTUP_TIMEOUT_MS / 1_000L} seconds",
                            )
                            return@Thread
                        }
                        if (probeCircuit(plannedSocksPort)) {
                            markCircuitReady(guid, "SOCKS circuit probe")
                            return@Thread
                        }
                        if (activeGuid != guid || connected || !torServiceStarted) return@Thread
                        val progress = bootstrapLog?.takeIf(File::isFile)?.useLines { lines ->
                            lines.filter { it.contains("Bootstrapped ") || it.contains("Problem bootstrapping") }
                                .lastOrNull()
                        }.orEmpty()
                        if (progress.isNotBlank() && progress != previousProgress) {
                            previousProgress = progress
                            lastProgressAt = System.nanoTime()
                            slowBootstrapReported = false
                            PingNgDiagnostics.record("Tor bootstrap: ${progress.take(180)}")
                        }
                        if (!slowBootstrapReported && System.nanoTime() - lastProgressAt >= 180_000_000_000L) {
                            slowBootstrapReported = true
                            PingNgDiagnostics.record(
                                "Tor bootstrap is still in progress; keeping the selected configuration connected",
                            )
                        }
                        // Once a circuit is ready, reflect it in the Tor badge promptly.
                        Thread.sleep(if (progress.contains("Bootstrapped 100%")) 500L else 1_000L)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "pingng-tor-circuit-watchdog").apply { isDaemon = true; start() }
        } catch (error: Throwable) {
            LogUtil.e(TAG, "Tor service start failed", error)
            PingNgDiagnostics.record("Tor service start failed", error)
            failTorStartup(app, guid, error.message.orEmpty().ifBlank { "Tor service failed to start" })
        }
    }

    @Synchronized
    fun stop() {
        val app = statusContext
        activeSnowflakeRunId = null
        circuitWatchdog?.interrupt()
        circuitWatchdog = null
        snowflakeStartupWatchdog?.interrupt()
        snowflakeStartupWatchdog = null
        connected = false
        publishConnectionState(ConnectionState.DISCONNECTED)
        socksPort = 0
        starting.set(false)
        activeGuid = null
        app?.let {
            unregisterReceiver(it)
            stopSnowflakeTransport(it)
            runCatching { it.stopService(Intent(it, EmbeddedTorService::class.java)) }
        }
        statusContext = null
        bootstrapLog?.let { runCatching { it.delete() } }
        bootstrapLog = null
        torServiceStarted = false
        snowflakeReady = false
        activeProfile = null
        activeUpstreamPort = 0
        activeUpstreamSocksPort = 0
        upstreamHttpPort = 0
        upstreamSocksPort = 0
        plannedSocksPort = 0
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter().apply { addAction(STATUS_ACTION); addAction(ERROR_ACTION) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver(context: Context) {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(statusReceiver) }
        receiverRegistered = false
    }

    private fun startSnowflakeTransport(app: Context, guid: String, runId: String) {
        if (activeGuid != guid || activeSnowflakeRunId != runId || !starting.get()) return
        if (snowflakeServiceStarted) return
        ContextCompat.registerReceiver(
            app,
            snowflakeReceiver,
            IntentFilter().apply {
                addAction(TorSnowflakeTransportService.ACTION_READY)
                addAction(TorSnowflakeTransportService.ACTION_FAILED)
                addAction(TorSnowflakeTransportService.ACTION_EVENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        snowflakeReceiverRegistered = true
        snowflakeReady = false
        snowflakeServiceStarted = true
        try {
            app.startService(
                Intent(app, TorSnowflakeTransportService::class.java)
                    .setAction(TorSnowflakeTransportService.ACTION_START)
                    .putExtra(TorSnowflakeTransportService.EXTRA_RUN_ID, runId),
            ) ?: error("Android did not start the Snowflake transport service")
            PingNgDiagnostics.record("Starting the Snowflake pluggable transport (run $runId)")
            snowflakeStartupWatchdog = Thread({
                try {
                    val deadline = System.nanoTime() + 45_000_000_000L
                    while (System.nanoTime() < deadline && activeGuid == guid &&
                        activeSnowflakeRunId == runId && starting.get() && !snowflakeReady
                    ) {
                        Thread.sleep(500L)
                    }
                    if (activeGuid == guid && activeSnowflakeRunId == runId && starting.get() && !snowflakeReady) {
                        failTorStartup(app, guid, "Snowflake did not open its local transport in time")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "pingng-tor-snowflake-startup-watchdog").apply { isDaemon = true; start() }
        } catch (error: Throwable) {
            failTorStartup(app, guid, error.message.orEmpty().ifBlank { "Unable to start Snowflake" })
        }
    }

    @Synchronized
    private fun failTorStartup(app: Context, guid: String, error: String) {
        if (activeGuid != guid || !starting.get()) return
        snowflakeStartupWatchdog?.interrupt()
        snowflakeStartupWatchdog = null
        stopSnowflakeTransport(app)
        circuitWatchdog?.interrupt()
        circuitWatchdog = null
        connected = false
        socksPort = 0
        torServiceStarted = false
        starting.set(false)
        publishConnectionState(ConnectionState.DISCONNECTED)
        PingNgDiagnostics.record("Tor startup failed: $error")
        CoreServiceManager.onTorFailed(error)
    }

    private fun stopSnowflakeTransport(app: Context) {
        snowflakeStartupWatchdog?.interrupt()
        snowflakeStartupWatchdog = null
        activeSnowflakeRunId = null
        snowflakeReady = false
        if (snowflakeReceiverRegistered) {
            runCatching { app.unregisterReceiver(snowflakeReceiver) }
            snowflakeReceiverRegistered = false
        }
        if (snowflakeServiceStarted) {
            runCatching { app.stopService(Intent(app, TorSnowflakeTransportService::class.java)) }
            snowflakeServiceStarted = false
        }
    }

    private fun isLoopbackProxyAddress(address: String): Boolean {
        val separator = address.lastIndexOf(':')
        if (separator <= 0 || separator == address.lastIndex) return false
        val host = address.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = address.substring(separator + 1).toIntOrNull() ?: return false
        return host in setOf("127.0.0.1", "localhost", "::1") && port in 1..65535
    }

    private fun ensurePorts() {
        if (plannedSocksPort !in 1..65535) plannedSocksPort = freePort(SOCKS_PORT_FALLBACK)
        if (upstreamHttpPort !in 1..65535) upstreamHttpPort = freePort(0)
        if (upstreamSocksPort !in 1..65535) upstreamSocksPort = freePort(0)
    }

    private fun freePort(preferred: Int): Int {
        if (preferred in 1..65535) {
            runCatching { ServerSocket(preferred).use { return it.localPort } }
        }
        return ServerSocket(0).use { it.localPort }
    }

    private fun selectUpstreamOutbound(profile: ProfileItem, outbounds: JsonArray): String {
        val preferred = if (profile.configType == EConfigType.WARP ||
            WarpPlusConfig.isDescription(profile.description) || MasterDnsBridge.isProfile(profile) || SoarBridge.isProfile(profile)
        ) "proxy" else null
        if (preferred != null && outbounds.any { it.isJsonObject && it.asJsonObject.get("tag")?.asString == preferred }) {
            return preferred
        }
        return outbounds.firstOrNull { outbound ->
            if (!outbound.isJsonObject) return@firstOrNull false
            val item = outbound.asJsonObject
            val protocol = item.get("protocol")?.asString.orEmpty().lowercase()
            val tag = item.get("tag")?.asString.orEmpty()
            tag.isNotBlank() && protocol !in setOf("direct", "freedom", "block", "blackhole", "dns", "wireguard") &&
                tag !in setOf(EGRESS_TAG, BLOCK_UDP_TAG)
        }?.asJsonObject?.get("tag")?.asString ?: "proxy"
    }

    /** Resolves only selected-proxy endpoints before Tor starts, avoiding a DNS bootstrap loop. */
    private fun addUpstreamHostMappings(
        dns: JsonObject,
        profile: ProfileItem,
        outbounds: JsonArray,
        selectedTag: String,
    ): List<String> {
        val candidates = linkedSetOf<String>()
        profile.server?.trim()?.takeIf(String::isNotBlank)?.let(candidates::add)
        outbounds.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == selectedTag
        }?.let { outbound -> collectAddresses(outbound, candidates) }
        candidates += TorSnowflakeConfig.bootstrapHosts
        val snowflakeHosts = TorSnowflakeConfig.bootstrapHosts.toSet()
        val snowflakeIps = linkedSetOf<String>()
        val hosts = dns.get("hosts")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { dns.add("hosts", it) }
        candidates.forEach { host ->
            if (Utils.isPureIpAddress(host) || host == "localhost") return@forEach
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
                ?.mapNotNull { it.hostAddress?.substringBefore('%') }
                ?.distinct().orEmpty()
            if (addresses.isNotEmpty()) {
                hosts.add(host, JsonArray().also { values -> addresses.forEach { values.add(it) } })
                if (host in snowflakeHosts) snowflakeIps += addresses
                PingNgDiagnostics.record("Tor bootstrap endpoint resolved before startup: $host")
            }
        }
        return snowflakeIps.toList()
    }

    private fun collectAddresses(value: com.google.gson.JsonElement, result: MutableSet<String>) {
        when {
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                if (key == "address" && child.isJsonPrimitive) result.add(child.asString)
                else collectAddresses(child, result)
            }
            value.isJsonArray -> value.asJsonArray.forEach { collectAddresses(it, result) }
        }
    }

    private fun rule(
        inboundTags: List<String>? = null,
        network: String? = null,
        port: String? = null,
        ipAddresses: List<String>? = null,
        outboundTag: String,
    ) = JsonObject().apply {
        addProperty("type", "field")
        inboundTags?.let { add("inboundTag", JsonArray().also { array -> it.forEach(array::add) }) }
        network?.let { addProperty("network", it) }
        port?.let { addProperty("port", it) }
        ipAddresses?.let { addresses -> add("ip", JsonArray().also { array -> addresses.forEach(array::add) }) }
        addProperty("outboundTag", outboundTag)
    }

    private fun isPreviousTorRule(rule: com.google.gson.JsonElement, userTags: List<String>, upstream: String): Boolean {
        if (!rule.isJsonObject) return false
        val obj = rule.asJsonObject
        val inbounds = obj.getAsJsonArray("inboundTag")?.map { it.asString }.orEmpty()
        val network = obj.get("network")?.asString
        val port = obj.get("port")?.asString
        val outbound = obj.get("outboundTag")?.asString
        return inbounds == listOf(UPSTREAM_TAG) ||
            (inbounds == listOf(SNOWFLAKE_UPSTREAM_TAG) && outbound == upstream) ||
            (inbounds == listOf(SNOWFLAKE_UPSTREAM_TAG) && outbound == SNOWFLAKE_UDP_TAG) ||
            inbounds == listOf(DNS_MODULE_TAG) ||
            (inbounds == userTags && network == "udp" && port == "53" && outbound == "dns-out") ||
            (inbounds == userTags && network == "udp" && (outbound == BLOCK_UDP_TAG || outbound == upstream)) ||
            (inbounds == userTags && network == "tcp" && outbound == BLOCK_IPV6_TAG) ||
            (inbounds == userTags && network == "tcp" && outbound == EGRESS_TAG) ||
            (inbounds.isEmpty() && network == "udp" && outbound == BLOCK_UDP_TAG) ||
            (inbounds.isEmpty() && network == "tcp" && outbound == EGRESS_TAG)
    }

    private fun removeTagged(array: JsonArray, tag: String) {
        for (index in array.size() - 1 downTo 0) {
            val element = array[index]
            if (element.isJsonObject && element.asJsonObject.get("tag")?.asString == tag) array.remove(index)
        }
    }
}
