package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dalvik.system.DexClassLoader
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.PsiphonStatus
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small PingNG adapter around the Shir o Khorshid Psiphon runtime.
 *
 * Psiphon is loaded from a private DEX because its generated gomobile API is not
 * an Android dependency of v2rayNG. The first Xray stage exposes a loopback HTTP
 * inbound; Psiphon uses that as its upstream proxy. After Psiphon reports
 * Connected, the Xray TUN route is reloaded through Psiphon's local SOCKS port.
 */
object PsiphonBridge {
    private const val TAG = "PingNG-Psiphon"
    private const val RUNTIME_DEX_ASSET = "pingng_psiphon_runtime.dex"
    // Android rejects a DEX that is still writable when DexClassLoader opens it.
    // Use a versioned filename so installs made with the old bridge cannot reuse
    // the writable copy already present in code_cache.
    private const val RUNTIME_DEX_FILE = "pingng_psiphon_runtime_ro_v2.dex"
    private const val CONFIG_ASSET = "pingng_psiphon_config.json"
    private const val ENTRIES_ASSET = "pingng_psiphon_server_entries.txt"
    private const val NATIVE_NAME = "libpingng_psiphon.so"
    private const val UPSTREAM_TAG = "pingng-psiphon-upstream"
    private const val EGRESS_TAG = "pingng-psiphon-egress"

    // Psiphon is always chained through PingNG's local HTTP upstream. Keep
    // the fallback path's protocol selection identical to the isolated
    // runtime service and the reference chained Psiphon mode.
    private val CHAINED_TUNNEL_PROTOCOLS = listOf(
        "SSH",
        "OSSH",
        "TLS-OSSH",
        "UNFRONTED-MEEK-OSSH",
        "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
        "SHADOWSOCKS-OSSH",
        "FRONTED-MEEK-OSSH",
        "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-HTTP-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH",
    )
    private val CDN_TUNNEL_PROTOCOLS = listOf(
        "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH",
    )

    @Volatile private var tunnel: Any? = null
    @Volatile private var loader: DexClassLoader? = null
    @Volatile private var upstreamHttpPort = 0
    @Volatile private var socksPort = 0
    @Volatile private var connected = false
    @Volatile private var activeGuid: String? = null
    @Volatile private var statusContext: Context? = null
    @Volatile private var runtimeRunning = false
    private val starting = AtomicBoolean(false)
    private val routeApplied = AtomicBoolean(false)
    private var receiverRegistered = false

    private val runtimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AppConfig.ACTION_PSIPHON_RUNTIME) return
            handleRuntimeEvent(intent)
        }
    }

    fun isRunning(): Boolean = runtimeRunning || tunnel != null
    fun isConnected(): Boolean = connected
    /** Port of the Psiphon SOCKS listener in the isolated runtime process. */
    fun activeSocksPort(): Int = socksPort.takeIf { it in 1..65535 } ?: 0
    fun selectedRegion(profile: ProfileItem): String = profile.psiphonRegion.orEmpty().ifBlank { "ANY" }.uppercase()

    /** Adds the bootstrap HTTP inbound and, after Psiphon connects, its final SOCKS route. */
    @Synchronized
    fun prepareCoreConfig(service: Service, profile: ProfileItem, raw: String): String {
        if (!profile.psiphonEnabled) return raw

        val root = JsonParser.parseString(raw).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray().also { root.add("inbounds", it) }
        if (upstreamHttpPort !in 1..65535) {
            upstreamHttpPort = findFreePort()
        }
        if (inbounds.none { it.isJsonObject && it.asJsonObject.get("tag")?.asString == UPSTREAM_TAG }) {
            inbounds.add(JsonObject().apply {
                addProperty("tag", UPSTREAM_TAG)
                addProperty("listen", "127.0.0.1")
                addProperty("port", upstreamHttpPort)
                addProperty("protocol", "http")
                add("settings", JsonObject().apply { addProperty("timeout", 0) })
            })
        }

        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray().also { root.add("outbounds", it) }
        // When Psiphon is enabled on WARP MASQUE, MASQUE is the upstream hop
        // for Psiphon's HTTP bootstrap. Once Psiphon reports a live SOCKS
        // listener, user TCP traffic must be routed to Psiphon so its exit IP
        // is visible to websites. Keeping the old MASQUE catch-all here made
        // Psiphon appear connected while every user request still exited via
        // WARP.
        val warpMasqueUpstream = profile.configType == EConfigType.WARP &&
            WarpMasqueConfig.isDescription(profile.description)
        if (warpMasqueUpstream) {
            PingNgDiagnostics.record("Psiphon enabled on WARP MASQUE; Psiphon will be final TCP egress")
        }
        val isWarpPlus = WarpPlusConfig.isDescription(profile.description)
        // WARP Plus is a generated two-hop chain. Do not let the first
        // incidental outbound (for example a DNS or fallback entry) become
        // Psiphon's upstream; the inner WARP tag is the only valid upstream
        // for this profile.
        val upstreamOutbound = if ((isWarpPlus || MasterDnsBridge.isProfile(profile)) && outbounds.any {
                it.isJsonObject && it.asJsonObject.get("tag")?.asString == "proxy"
            }) {
            "proxy"
        } else outbounds.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString?.let(::isUsableOutbound) == true
        }?.asJsonObject?.get("tag")?.asString ?: "proxy"

        val routing = root.getAsJsonObject("routing") ?: JsonObject().also {
            it.addProperty("domainStrategy", "AsIs")
            it.add("rules", JsonArray())
            root.add("routing", it)
        }
        val oldRules = routing.getAsJsonArray("rules") ?: JsonArray()
        val rules = JsonArray()
        rules.add(JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().also { it.add(UPSTREAM_TAG) })
            addProperty("outboundTag", upstreamOutbound)
        })
        oldRules.forEach { rule ->
            if (rule.isJsonObject && rule.asJsonObject.getAsJsonArray("inboundTag")?.any {
                    it.asString == UPSTREAM_TAG
                } == true) return@forEach
            rules.add(rule)
        }

        if (connected && socksPort in 1..65535) {
            if (outbounds.none { it.isJsonObject && it.asJsonObject.get("tag")?.asString == EGRESS_TAG }) {
                outbounds.add(JsonObject().apply {
                    addProperty("tag", EGRESS_TAG)
                    addProperty("protocol", "socks")
                    add("settings", JsonObject().apply {
                        addProperty("version", "5")
                        addProperty("udp", false)
                        add("servers", JsonArray().also { servers ->
                            servers.add(JsonObject().apply {
                                addProperty("address", "127.0.0.1")
                                addProperty("port", socksPort)
                            })
                        })
                    })
                    // Keep the local Psiphon hop TCP-only and IPv4. This is
                    // especially important for WARP Plus where the generated
                    // chain may otherwise inherit an unsuitable UDP/IPv6
                    // dial path from the WireGuard outbounds.
                    add("streamSettings", JsonObject().apply {
                        addProperty("network", "tcp")
                        add("sockopt", JsonObject().apply {
                            addProperty("domainStrategy", "UseIPv4")
                        })
                    })
                })
            }
            val finalRules = JsonArray()
            // Custom JSON files do not necessarily call their TUN/mixed inbound
            // "tun". Route every user-facing inbound explicitly; otherwise the
            // Psiphon switch appears enabled but traffic keeps using the old
            // route. UDP stays on Xray because Psiphon's listener is TCP-only.
            val userInboundTags = inbounds.mapNotNull { inbound ->
                inbound.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeUnless { it == UPSTREAM_TAG }
            }.distinct().ifEmpty { listOf("tun") }
            // Keep DNS interception ahead of the Psiphon catch-all. The
            // generic UDP rule below used to win over CoreConfigManager's
            // UDP/53 -> dns-out rule, so DNS was sent to the WARP SOCKS hop
            // and browsers failed while cached-IP apps such as Telegram kept
            // working. Only add this rule when the prepared config really has
            // a DNS outbound; custom configs without one must retain their
            // original behavior instead of referencing a missing tag.
            val hasDnsOutbound = outbounds.any {
                it.isJsonObject &&
                    it.asJsonObject.get("tag")?.takeIf { tag -> tag.isJsonPrimitive }?.asString == "dns-out" &&
                    it.asJsonObject.get("protocol")?.takeIf { protocol -> protocol.isJsonPrimitive }
                        ?.asString?.equals("dns", ignoreCase = true) == true
            }
            if (hasDnsOutbound) {
                finalRules.add(JsonObject().apply {
                    addProperty("type", "field")
                    add("inboundTag", JsonArray().also { tags -> userInboundTags.forEach(tags::add) })
                    addProperty("network", "udp")
                    addProperty("port", "53")
                    addProperty("outboundTag", "dns-out")
                })
                PingNgDiagnostics.record("Psiphon DNS interception: UDP/53 -> dns-out")
            }
            finalRules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().also { tags -> userInboundTags.forEach(tags::add) })
                addProperty("network", "udp")
                addProperty("outboundTag", upstreamOutbound)
            })
            finalRules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().also { tags -> userInboundTags.forEach(tags::add) })
                addProperty("network", "tcp")
                addProperty("outboundTag", EGRESS_TAG)
            })
            rules.forEach(finalRules::add)
            routing.add("rules", finalRules)
            PingNgDiagnostics.record(
                "Psiphon final route applied: TCP -> $EGRESS_TAG; upstream -> $upstreamOutbound",
            )
        } else {
            routing.add("rules", rules)
        }
        return root.toString()
    }

    fun startAfterCoreConnected(service: Service, guid: String, profile: ProfileItem) {
        if (!profile.psiphonEnabled || isRunning() || !starting.compareAndSet(false, true)) return
        activeGuid = guid
        statusContext = service.applicationContext
        runtimeRunning = true
        registerRuntimeReceiver(service)
        publishStatus(PsiphonStatus.CONNECTING, guid, service)
        try {
            val intent = Intent(service, com.v2ray.ang.service.PsiphonRuntimeService::class.java)
                .setAction(AppConfig.PSIPHON_RUNTIME_START)
                .putExtra(AppConfig.EXTRA_PSIPHON_GUID, guid)
                .putExtra(AppConfig.EXTRA_PSIPHON_REGION, selectedRegion(profile))
                .putExtra(AppConfig.EXTRA_PSIPHON_MODE, profile.psiphonMode.orEmpty().ifBlank { "auto" })
                .putExtra(AppConfig.EXTRA_PSIPHON_CDN_IPS, profile.psiphonCdnIps.orEmpty())
                .putExtra(AppConfig.EXTRA_PSIPHON_CDN_SNI, profile.psiphonCdnSni.orEmpty())
                .putExtra(AppConfig.EXTRA_PSIPHON_CDN_SETS, profile.psiphonCdnSets.orEmpty())
                .putExtra(AppConfig.EXTRA_PSIPHON_UPSTREAM_PORT, upstreamHttpPort)
            ContextCompat.startForegroundService(service, intent)
        } catch (e: Throwable) {
            runtimeRunning = false
            starting.set(false)
            val cause = unwrapInvocation(e)
            PingNgDiagnostics.record("Psiphon process start failed", cause)
            LogUtil.e(TAG, "Psiphon process start failed", cause)
            publishStatus(PsiphonStatus.FAILED, guid, service)
        }
    }

    @Synchronized
    fun stop() {
        val guid = activeGuid
        val context = statusContext
        runtimeRunning = false
        context?.let { unregisterRuntimeReceiver(it) }
        context?.let {
            val runtimeIntent = Intent(it, com.v2ray.ang.service.PsiphonRuntimeService::class.java)
            runCatching {
                val stopIntent = Intent(runtimeIntent)
                    .setAction(AppConfig.PSIPHON_RUNTIME_STOP)
                ContextCompat.startForegroundService(it, stopIntent)
            }
            // stopService is also needed when Android tears down the old
            // instance before delivering the explicit stop command.
            runCatching { it.stopService(runtimeIntent) }
        }
        val active = tunnel
        tunnel = null
        connected = false
        routeApplied.set(false)
        socksPort = 0
        upstreamHttpPort = 0
        starting.set(false)
        if (active != null) {
            try {
                active.javaClass.getMethod("stop").invoke(active)
            } catch (e: Throwable) {
                LogUtil.e(TAG, "Psiphon stop failed", e)
            }
        }
        publishStatus(PsiphonStatus.STOPPED, guid, context)
        activeGuid = null
        statusContext = null
    }

    private fun registerRuntimeReceiver(service: Service) {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            service.applicationContext,
            runtimeReceiver,
            IntentFilter(AppConfig.ACTION_PSIPHON_RUNTIME),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterRuntimeReceiver(context: Context) {
        if (!receiverRegistered) return
        runCatching { context.applicationContext.unregisterReceiver(runtimeReceiver) }
        receiverRegistered = false
    }

    private fun handleRuntimeEvent(intent: Intent) {
        val guid = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_GUID)
        if (guid.isNullOrBlank() || guid != activeGuid) return
        val state = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_STATE).orEmpty()
        val context = statusContext ?: return
        when (state) {
            PsiphonStatus.CONNECTING -> {
                publishStatus(PsiphonStatus.CONNECTING, guid, context)
            }
            PsiphonStatus.CONNECTED -> {
                val reportedPort = intent.getIntExtra(AppConfig.EXTRA_PSIPHON_SOCKS_PORT, 0)
                val duplicateConnected = connected && socksPort == reportedPort &&
                    reportedPort in 1..65535
                socksPort = reportedPort
                connected = socksPort in 1..65535
                PingNgDiagnostics.record("Psiphon is working; SOCKS=127.0.0.1:$socksPort")
                publishStatus(PsiphonStatus.CONNECTED, guid, context)
                // The runtime can emit CONNECTED more than once for the same
                // SOCKS listener. Rebuilding Xray for every duplicate tears
                // down active sockets and makes WARP Plus appear connected
                // while every website request times out.
                if (!duplicateConnected) routeApplied.set(false)
                if (connected && !duplicateConnected && routeApplied.compareAndSet(false, true)) {
                    CoreServiceManager.onPsiphonConnected()
                }
            }
            PsiphonStatus.FAILED -> {
                runtimeRunning = false
                connected = false
                socksPort = 0
                starting.set(false)
                val message = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_MESSAGE).orEmpty()
                PingNgDiagnostics.record("Psiphon runtime failed${message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                publishStatus(PsiphonStatus.FAILED, guid, context)
                CoreServiceManager.onPsiphonFailed(message)
            }
            PsiphonStatus.STOPPED -> {
                runtimeRunning = false
                connected = false
                socksPort = 0
                routeApplied.set(false)
                starting.set(false)
                publishStatus(PsiphonStatus.STOPPED, guid, context)
                if (CoreServiceManager.isRunning()) CoreServiceManager.onPsiphonDisconnected()
            }
        }
    }

    private fun startInternal(service: Service, guid: String, profile: ProfileItem) {
        val runtimeDir = File(service.codeCacheDir, "pingng-psiphon-runtime").apply { mkdirs() }
        val dexFile = materializeReadOnlyRuntimeDex(service, runtimeDir)
        val runtimeNativeDir = materializeRuntimeNativeLibrary(service, runtimeDir)

        val parent = PsiphonBridge::class.java.classLoader
        // The gomobile runtime must be visible to the same class loader that owns
        // psi.Psi. Otherwise Android can load the .so successfully but still report
        // "No implementation found" for its Java native methods.
        val runtimeLoader = PsiphonDexClassLoader(
            dexFile.absolutePath,
            runtimeDir.absolutePath,
            runtimeNativeDir.absolutePath,
            parent,
        )
        // The Psiphon DEX loads "gojni" from this private directory during
        // go.Seq initialization. Keeping that file outside the APK's shared
        // nativeLibraryDir prevents Android from binding it to Xray's copy.
        Class.forName("go.Seq", true, runtimeLoader)
            .getMethod("setContext", Context::class.java)
            .invoke(null, service.applicationContext)
        // Resolve the native binding through the same loader before the Go
        // goroutine is created. This also gives FindClass a loader-associated
        // reference on Android 15, where the previous runtime crashed with
        // `CallStaticIntMethod received NULL jclass`.
        Class.forName("psi.Psi", true, runtimeLoader)
        PingNgDiagnostics.record("Psiphon gomobile runtime initialized")
        val tunnelClass = runtimeLoader.loadClass("ca.psiphon.PsiphonTunnel")
        val hostClass = runtimeLoader.loadClass("ca.psiphon.PsiphonTunnel\$HostService")
        val config = buildConfig(service, profile)
        val entries = service.assets.open(ENTRIES_ASSET).bufferedReader().use { it.readText() }

        val host = Proxy.newProxyInstance(runtimeLoader, arrayOf(hostClass), InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getContext" -> service
                "getPsiphonConfig" -> config
                "loadLibrary" -> {
                    // PsiphonTunnel's default method calls System.loadLibrary from
                    // the runtime DEX class loader. Keep the APK's private library
                    // name while preserving that class-loader association.
                    val requested = args?.firstOrNull()?.toString().orEmpty()
                    if (requested == "gojni") {
                        // Already loaded explicitly above under the runtime
                        // loader. Do not load the colliding Xray libgojni.so.
                        null
                    } else {
                        null
                    }
                }
                "bindToDevice" -> null
                "onListeningSocksProxyPort" -> {
                    socksPort = (args?.firstOrNull() as? Int ?: 0).takeIf { it in 1..65535 } ?: socksPort
                    if (socksPort in 1..65535) {
                        LogUtil.i(TAG, "Psiphon SOCKS is ready on 127.0.0.1:$socksPort")
                        PingNgDiagnostics.record("Psiphon SOCKS ready on 127.0.0.1:$socksPort")
                    }
                    null
                }
                "onConnected" -> {
                    connected = true
                    val region = selectedRegion(profile)
                    LogUtil.i(TAG, "Psiphon is working; region=$region")
                    PingNgDiagnostics.record("Psiphon is working; region=$region")
                    publishStatus(PsiphonStatus.CONNECTED, guid, service)
                    Thread {
                        repeat(40) {
                            if (tunnel == null || !connected) return@Thread
                            if (socksPort in 1..65535 && routeApplied.compareAndSet(false, true)) {
                                CoreServiceManager.onPsiphonConnected()
                                return@Thread
                            }
                            Thread.sleep(250L)
                        }
                    }.start()
                    null
                }
                "onUpstreamProxyError" -> {
                    val message = args?.firstOrNull()?.toString().orEmpty()
                    LogUtil.w(TAG, "Psiphon upstream proxy error: $message")
                    PingNgDiagnostics.record("Psiphon upstream proxy error: $message")
                    null
                }
                "onExiting" -> {
                    connected = false
                    socksPort = 0
                    routeApplied.set(false)
                    PingNgDiagnostics.record("Psiphon stopped")
                    publishStatus(PsiphonStatus.STOPPED, guid, service)
                    if (tunnel != null) Thread { CoreServiceManager.onPsiphonDisconnected() }.start()
                    null
                }
                "onDiagnosticMessage" -> {
                    LogUtil.d(TAG, args?.firstOrNull()?.toString().orEmpty())
                    null
                }
                else -> defaultValue(method)
            }
        })

        val instance = tunnelClass.getMethod("newPsiphonTunnel", hostClass).invoke(null, host)
        instance.javaClass.getMethod("setVpnMode", Boolean::class.javaPrimitiveType!!).invoke(instance, false)
        loader = runtimeLoader
        tunnel = instance
        val currentThread = Thread.currentThread()
        val previousLoader = currentThread.contextClassLoader
        currentThread.contextClassLoader = runtimeLoader
        try {
            instance.javaClass.getMethod("startTunneling", String::class.java).invoke(instance, entries)
        } catch (e: Throwable) {
            tunnel = null
            connected = false
            throw unwrapInvocation(e)
        } finally {
            currentThread.contextClassLoader = previousLoader
        }
        // Read the port after start; callbacks are asynchronous in tunnel-core.
        Thread {
            repeat(60) {
                if (tunnel !== instance) return@Thread
                val port = runCatching {
                    instance.javaClass.getMethod("getLocalSocksProxyPort").invoke(instance) as Int
                }.getOrDefault(0)
                if (port in 1..65535) {
                    socksPort = port
                    return@Thread
                }
                Thread.sleep(250L)
            }
        }.start()
    }

    /**
     * Copies the asset to an app-private optimized-code directory and removes
     * write permission before DexClassLoader sees it. Android 14+ enforces this
     * check and throws "Writable dex file ... is not allowed" otherwise.
     */
    private fun materializeReadOnlyRuntimeDex(service: Service, runtimeDir: File): File {
        val dexFile = File(runtimeDir, RUNTIME_DEX_FILE)
        if (!dexFile.isFile || dexFile.length() < 1024) {
            service.assets.open(RUNTIME_DEX_ASSET).use { input ->
                dexFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dexFile.setReadable(true, false)
        dexFile.setWritable(false, false)
        dexFile.setExecutable(false, false)
        check(!dexFile.canWrite()) {
            "Psiphon runtime DEX is writable: ${dexFile.absolutePath}"
        }
        return dexFile
    }

    /**
     * Gives the generated gomobile DEX the library name it requests while
     * keeping Psiphon's native runtime in its own ClassLoader directory.
     */
    private fun materializeRuntimeNativeLibrary(service: Service, runtimeDir: File): File {
        val source = File(service.applicationInfo.nativeLibraryDir, NATIVE_NAME)
        check(source.isFile) { "Psiphon native library not found: ${source.absolutePath}" }
        val nativeDir = File(runtimeDir, "lib").apply { mkdirs() }
        val target = File(nativeDir, "libgojni.so")
        if (!target.isFile || target.length() != source.length()) {
            source.copyTo(target, overwrite = true)
        }
        target.setReadable(true, false)
        target.setWritable(false, false)
        target.setExecutable(true, false)
        check(target.isFile && target.canRead()) {
            "Psiphon runtime library is not readable: ${target.absolutePath}"
        }
        return nativeDir
    }

    private fun buildConfig(service: Service, profile: ProfileItem): String {
        val base = service.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(base).asJsonObject
        val region = selectedRegion(profile).trim().uppercase(Locale.ROOT)
            .takeUnless { it.isBlank() || it == "ANY" || it == "AUTO" }.orEmpty()
        root.addProperty("EgressRegion", region)
        root.addProperty("TunnelWholeDevice", 0)
        val configuredProxy = SettingsManager.getConnectHttpProxy()
        val upstreamProxyUrl = configuredProxy?.asHttpUrl() ?: "http://127.0.0.1:$upstreamHttpPort"
        root.addProperty("UpstreamProxyUrl", upstreamProxyUrl)
        if (configuredProxy != null) {
            PingNgDiagnostics.record(
                "Connect through HTTP proxy: Psiphon upstream set to ${configuredProxy.host}:${configuredProxy.port}",
            )
        }
        val dataRoot = File(service.filesDir, "pingng-psiphon").apply { mkdirs() }
        val dataStore = File(dataRoot, "datastore").apply { mkdirs() }
        val oslDirectory = File(service.filesDir, "osl").apply { mkdirs() }
        root.addProperty("DataRootDirectory", dataRoot.absolutePath)
        root.addProperty("DataStoreDirectory", dataStore.absolutePath)
        root.addProperty("MigrateDataStoreDirectory", service.filesDir.absolutePath)
        root.addProperty("MigrateObfuscatedServerListDownloadDirectory", oslDirectory.absolutePath)
        root.addProperty("MigrateRemoteServerListDownloadFilename", File(service.filesDir, "remote_server_list").absolutePath)
        root.addProperty("EstablishTunnelTimeoutSeconds", 0)
        root.addProperty("UpstreamProxyAllowAllServerEntrySources", true)
        applyTunnelProtocolMode(root, profile.psiphonMode.orEmpty())
        addCdnFrontingConfig(root, profile)
        return root.toString()
    }

    /** Restricts Psiphon to the selected shape when it is chained upstream. */
    private fun applyTunnelProtocolMode(root: JsonObject, mode: String) {
        val normalized = mode.trim().lowercase(Locale.ROOT)
        val protocols = when (normalized) {
            "cdn" -> CDN_TUNNEL_PROTOCOLS
            "direct" -> CHAINED_TUNNEL_PROTOCOLS.filterNot { it.startsWith("FRONTED-") }
            else -> CHAINED_TUNNEL_PROTOCOLS
        }
        root.add("LimitTunnelProtocols", JsonArray().also { values -> protocols.forEach(values::add) })
        if (normalized == "cdn" || normalized == "direct") {
            root.addProperty("DisableTactics", true)
        }
    }

    /** Applies the same CDN fronting fields to the in-process fallback path. */
    private fun addCdnFrontingConfig(root: JsonObject, profile: ProfileItem) {
        if (profile.psiphonMode.orEmpty().equals("direct", ignoreCase = true)) return
        fun candidates(raw: String): List<String> = raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map(String::trim).filter(String::isNotBlank)
        val ips = candidates(profile.psiphonCdnIps.orEmpty())
        val sni = candidates(profile.psiphonCdnSni.orEmpty())
        val sets = candidates(profile.psiphonCdnSets.orEmpty())
        if (!profile.psiphonMode.orEmpty().equals("cdn", ignoreCase = true) && ips.isEmpty() && sni.isEmpty() && sets.isEmpty()) return
        if (ips.isNotEmpty()) {
            root.add("FrontedMeekCDNScanSpec", JsonObject().apply {
                add("IPCandidates", JsonArray().also { values -> ips.forEach(values::add) })
                if (sni.isNotEmpty()) add("SNIServerNames", JsonArray().also { values -> sni.forEach(values::add) })
            })
        }
        if (ips.isEmpty() || sets.isNotEmpty()) root.addProperty("FrontedMeekCDNScanUseBuiltInSpec", true)
        if (sets.isNotEmpty()) root.add("FrontedMeekCDNScanBuiltInSets", JsonArray().also { values -> sets.forEach(values::add) })
    }

    private fun isUsableOutbound(tag: String): Boolean = tag !in setOf(
        "direct", "block", "dns", "freedom", "blackhole", EGRESS_TAG
    )

    private fun findFreePort(): Int =
        java.net.ServerSocket(0).use { it.localPort }

    private fun defaultValue(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private fun publishStatus(state: String, guid: String?, context: Context?) {
        if (guid.isNullOrBlank() || context == null) return
        MessageHelper.sendMsg2UI(context, AppConfig.MSG_PSIPHON_STATUS, PsiphonStatus(guid, state))
    }

    private fun unwrapInvocation(error: Throwable): Throwable {
        val invocation = error as? InvocationTargetException ?: return error
        return invocation.targetException?.let(::unwrapInvocation) ?: error
    }

    /** Keeps Psiphon's generated gomobile classes separate from Xray's copy of go.Seq. */
    private class PsiphonDexClassLoader(
        dexPath: String,
        optimizedDirectory: String,
        librarySearchPath: String?,
        parent: ClassLoader?,
    ) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
        private fun belongsToPsiphon(name: String): Boolean =
            name == "go" || name.startsWith("go.") ||
                name == "psi" || name.startsWith("psi.") ||
                name == "ca.psiphon" || name.startsWith("ca.psiphon.")

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            // getClassLoadingLock() is not available on the Android API level
            // targeted by this project. The loader instance is sufficient as
            // a stable lock because all Psiphon classes use this loader.
            synchronized(this) {
                var loaded = findLoadedClass(name)
                if (loaded == null && belongsToPsiphon(name)) {
                    loaded = runCatching { findClass(name) }.getOrNull()
                }
                if (loaded == null) loaded = super.loadClass(name, resolve)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }
}
