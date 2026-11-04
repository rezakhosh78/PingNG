package com.v2ray.ang.core

import android.content.Context
import android.app.Service
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import java.io.File
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Registers WARP keys directly first, then through the built-in proxy fallbacks. */
object WarpRegistrationProxy {
    const val AUTO = "__AUTO__"
    const val AUTO_LABEL = "Auto"
    const val PROXY_1 = "__PINGNG_PROXY_1_VLESS__"
    const val PROXY_1_LABEL = "Proxy-1"
    const val PROXY_2 = "__PINGNG_PROXY_2_WIREGUARD__"
    const val PROXY_2_LABEL = "Proxy-2"
    @Deprecated("Use AUTO")
    const val DIRECT = AUTO
    @Deprecated("Use AUTO_LABEL")
    const val DIRECT_LABEL = AUTO_LABEL
    @Deprecated("Use AUTO")
    const val TUNNEL = AUTO
    @Deprecated("Use AUTO_LABEL")
    const val TUNNEL_LABEL = AUTO_LABEL
    private const val BUILTIN_PROXY_SOCKS_PORT = 10808
    private const val LEGACY_DIRECT = "__DIRECT__"
    private const val LEGACY_TUNNEL = "__TUNNEL__"
    private const val LEGACY_PROXY_1_WIREGUARD = "__PINGNG_PROXY_1_WIREGUARD__"
    private const val LEGACY_PUBLIC_PROXY_REMARK = "Proxy-1"
    /** Proxy startup and cleanup mutate process-wide selected-profile state. */
    private val registrationMutex = Mutex()

    data class Choice(val guid: String, val label: String)

    /** Migrates old Direct/Tunnel choices to Auto and the old WireGuard Proxy-1 to Proxy-2. */
    fun normalizeSelection(value: String?): String = when {
        value.isNullOrBlank() || value == LEGACY_DIRECT || value == LEGACY_TUNNEL ||
            value == "__AUTO__" || value?.equals(AUTO_LABEL, ignoreCase = true) == true ||
            value?.equals("Direct", ignoreCase = true) == true || value?.equals("Tunnel", ignoreCase = true) == true -> AUTO
        value == LEGACY_PROXY_1_WIREGUARD || value?.equals(PROXY_2_LABEL, ignoreCase = true) == true -> PROXY_2
        value?.equals(PROXY_1_LABEL, ignoreCase = true) == true -> PROXY_1
        else -> value
    }

    fun isAutoChoice(value: String?): Boolean = normalizeSelection(value) == AUTO
    fun isProxy1Choice(value: String?): Boolean = normalizeSelection(value) == PROXY_1
    fun isProxy2Choice(value: String?): Boolean = normalizeSelection(value) == PROXY_2
    @Deprecated("Use isAutoChoice")
    fun isDirectChoice(value: String?): Boolean = isAutoChoice(value)
    @Deprecated("Use isAutoChoice")
    fun isTunnelChoice(value: String?): Boolean = isAutoChoice(value)

    fun hasMasqueRegistrationReady(context: Context, guid: String, profile: ProfileItem): Boolean =
        hasMasqueCredentials(profile) && profile.warpMasqueRegistrationGuid == guid &&
            (!WarpMasqueConfig.shouldAutoScan(profile) || EndpointScannerCli.hasMasqueH2Account(context, guid))

    suspend fun prepareSelectedMasqueRegistration(service: Service) {
        val guid = MmkvManager.getSelectServer() ?: return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (profile.configType != EConfigType.WARP) return
        if (!WarpMasqueConfig.isDescription(profile.description)) return
        if (hasMasqueRegistrationReady(service, guid, profile)) return
        prepareMasqueRegistration(
            service = service,
            targetGuid = guid,
            targetProfile = profile,
            proxyGuid = normalizeSelection(profile.warpRegistrationProxyGuid),
        )
    }

    /** Removes the retired WARP-based Proxy-1 profile and migrates saved choices. */
    fun removeLegacyMasqueProxy1() {
        val legacyGuids = MmkvManager.decodeAllServerList().filter { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@filter false
            profile.configType == EConfigType.WARP &&
                profile.remarks.trim() == LEGACY_PUBLIC_PROXY_REMARK &&
                profile.server == "162.159.198.7" &&
                profile.serverPort == "443" &&
                profile.warpMasqueSni.equals("Soft98.ir", ignoreCase = true)
        }.toMutableSet()
        MmkvManager.decodeAllServerList().forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            if (profile.warpRegistrationProxyGuid == "__AUTO__" ||
                    profile.warpRegistrationProxyGuid == LEGACY_DIRECT ||
                    profile.warpRegistrationProxyGuid == LEGACY_TUNNEL ||
                    profile.warpRegistrationProxyGuid == LEGACY_PROXY_1_WIREGUARD ||
                    profile.warpRegistrationProxyGuid?.let { it in legacyGuids } == true) {
                profile.warpRegistrationProxyGuid = when {
                    profile.warpRegistrationProxyGuid == LEGACY_PROXY_1_WIREGUARD -> PROXY_2
                    profile.warpRegistrationProxyGuid?.let { it in legacyGuids } == true -> PROXY_1
                    else -> AUTO
                }
                MmkvManager.encodeServerConfig(guid, profile)
            }
        }
        if (MmkvManager.getSelectServer()?.let { it in legacyGuids } == true) MmkvManager.setSelectServer("")
        legacyGuids.forEach(MmkvManager::removeServer)
    }

    fun choices(excludeGuid: String = ""): List<Choice> {
        val profiles = MmkvManager.decodeAllServerList()
            .asSequence()
            .filter { it != excludeGuid }
            .mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
            .filter { (_, profile) -> profile.managedBy.isNullOrBlank() && profile.warpRegistrationInternalProxy != true }
            .toList()
        val duplicateNames = profiles.groupingBy { (_, profile) ->
            profile.remarks.trim().ifBlank { profile.configType.toString() }
        }.eachCount()
        val choices = profiles.map { (guid, profile) ->
            val remark = profile.remarks.trim().ifBlank { profile.configType.toString() }
            val target = profile.server?.takeIf { it.isNotBlank() }
                ?.let { " · $it:${profile.serverPort}" }.orEmpty()
            Choice(guid, if ((duplicateNames[remark] ?: 0) > 1) "$remark$target" else remark)
        }
        return WarpProxyChoiceLabels.makeUnique(
            (listOf(Choice(PROXY_1, PROXY_1_LABEL), Choice(PROXY_2, PROXY_2_LABEL)) + choices).map {
                WarpProxyChoiceLabel(it.guid, it.label)
            },
            reservedLabels = setOf(AUTO_LABEL),
        ).map { Choice(it.guid, it.label) }
    }

    /** Registers a MASQUE profile before TUN setup using Auto or an explicitly selected Proxy. */
    suspend fun prepareMasqueRegistration(
        service: Service,
        targetGuid: String,
        targetProfile: ProfileItem,
        proxyGuid: String,
    ) = registrationMutex.withLock {
        withContext(Dispatchers.IO) {
            fun scannerMissing() = WarpMasqueConfig.shouldAutoScan(targetProfile) &&
                !EndpointScannerCli.hasMasqueH2Account(service, targetGuid)
            if (hasMasqueRegistrationReady(service, targetGuid, targetProfile)) return@withContext

            if (isAutoChoice(proxyGuid)) {
                PingNgDiagnostics.record("Using Auto: trying MASQUE authorization directly before Proxy-1 and Proxy-2")
                val directFailure = try {
                    registerMasqueDirect(service, targetGuid, targetProfile)
                    if (scannerMissing()) EndpointScannerCli.prepareMasqueH2Account(service, targetGuid)
                    null
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    PingNgDiagnostics.record("Direct MASQUE authorization failed; retrying through Proxy-1", error)
                    error
                }
                if (directFailure == null) {
                    PingNgDiagnostics.record("Auto MASQUE authorization completed directly")
                    return@withContext
                }

                var proxy1Failure: Throwable? = null
                try {
                    PingNgDiagnostics.record("Starting Proxy-1 for MASQUE authorization fallback")
                    withEphemeralBuiltinProxy(PROXY_1) {
                        registerMasqueThroughProxy(service, targetGuid, targetProfile, PROXY_1)
                    }
                    PingNgDiagnostics.record("MASQUE authorization completed through Proxy-1")
                    return@withContext
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    proxy1Failure = error
                    PingNgDiagnostics.record("Proxy-1 MASQUE authorization failed; retrying through Proxy-2", error)
                }
                try {
                    withEphemeralBuiltinProxy(PROXY_2) {
                        registerMasqueThroughProxy(service, targetGuid, targetProfile, PROXY_2)
                    }
                    PingNgDiagnostics.record("MASQUE authorization completed through Proxy-2")
                } catch (proxy2Failure: Throwable) {
                    if (proxy2Failure is CancellationException) throw proxy2Failure
                    throw IllegalStateException(
                        "Direct, Proxy-1, and Proxy-2 MASQUE authorization attempts failed: " +
                            (proxy2Failure.message ?: proxy2Failure.javaClass.simpleName),
                        proxy2Failure,
                    ).also {
                        it.addSuppressed(directFailure)
                        proxy1Failure?.let(it::addSuppressed)
                    }
                }
                return@withContext
            }

            if (isProxy1Choice(proxyGuid)) {
                PingNgDiagnostics.record("Using Proxy-1 for MASQUE authorization")
                withEphemeralBuiltinProxy(PROXY_1) {
                    registerMasqueThroughProxy(service, targetGuid, targetProfile, PROXY_1)
                }
                return@withContext
            }
            if (isProxy2Choice(proxyGuid)) {
                PingNgDiagnostics.record("Using Proxy-2 for MASQUE authorization")
                withEphemeralBuiltinProxy(PROXY_2) {
                    registerMasqueThroughProxy(service, targetGuid, targetProfile, PROXY_2)
                }
                return@withContext
            }

            registerMasqueThroughProxy(service, targetGuid, targetProfile, proxyGuid)
        }
    }

    private suspend fun registerMasqueDirect(service: Service, targetGuid: String, targetProfile: ProfileItem) {
        if (!hasMasqueCredentials(targetProfile) || targetProfile.warpMasqueRegistrationGuid != targetGuid) {
            targetProfile.warpMasqueConfigJson = null
            if (targetProfile.warpMasqueRegistrationGuid != targetGuid) {
                File(service.filesDir, "warp-masque/$targetGuid.json").delete()
            }
            WarpMasqueBridge.prepareRegistration(service, targetGuid, targetProfile)
            targetProfile.warpMasqueRegistrationGuid = targetGuid
            MmkvManager.encodeServerConfig(targetGuid, targetProfile)
        }
    }

    private suspend fun <T> withBuiltinProxyProfile(
        context: Context,
        guid: String,
        action: suspend (Proxy) -> T,
    ): T = withEphemeralBuiltinProxy(guid) {
        withProxyProfile(context, guid, action)
    }

    private suspend fun <T> withEphemeralBuiltinProxy(guid: String, action: suspend () -> T): T {
        require(guid == PROXY_1 || guid == PROXY_2) { "Unknown built-in Proxy selection" }
        return try {
            MmkvManager.encodeEphemeralServerConfig(guid, builtinProxyProfile(guid))
            MmkvManager.encodeEphemeralServerRaw(guid, builtinProxyConfig(guid))
            action()
        } finally {
            MmkvManager.removeEphemeralServerRaw(guid)
            MmkvManager.removeEphemeralServerConfig(guid)
        }
    }

    private fun builtinProxyProfile(guid: String): ProfileItem = ProfileItem.create(EConfigType.CUSTOM).apply {
        remarks = if (guid == PROXY_1) PROXY_1_LABEL else PROXY_2_LABEL
        warpRegistrationInternalProxy = true
    }

    private fun builtinProxyConfig(guid: String): String = if (guid == PROXY_1) PROXY_1_CONFIG else PROXY_2_CONFIG

    private const val PROXY_1_CONFIG = """
{
  "remarks": "Proxy-1",
  "version": { "min": "26.2.6" },
  "log": { "loglevel": "warning" },
  "dns": { "servers": ["1.1.1.1"], "queryStrategy": "UseIPv4" },
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "mixed",
      "settings": { "auth": "noauth", "udp": true },
      "tag": "mixed-in"
    }
  ],
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [{
          "address": "pingng.novintech.site",
          "port": 443,
          "users": [{
            "id": "7b102311-43fd-4e8f-877e-8090623c101d",
            "encryption": "none"
          }]
        }]
      },
      "streamSettings": {
        "network": "ws",
        "security": "tls",
        "tlsSettings": {
          "serverName": "rough-field-378e.pingplas4.workers.dev",
          "fingerprint": "chrome",
          "allowInsecure": false
        },
        "wsSettings": {
          "path": "/www?ed=2560",
          "headers": { "Host": "rough-field-378e.pingplas4.workers.dev" }
        }
      },
      "tag": "proxy"
    },
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "domain": ["geosite:private"], "outboundTag": "direct", "type": "field" },
      { "ip": ["geoip:private"], "outboundTag": "direct", "type": "field" },
      { "network": "tcp,udp", "outboundTag": "proxy", "type": "field" }
    ]
  },
  "stats": {}
}
"""

    private const val PROXY_2_CONFIG = """
{
  "remarks": "Proxy-2",
  "version": { "min": "26.2.6" },
  "log": { "loglevel": "warning" },
  "dns": {
    "servers": [
      { "address": "1.1.1.1", "tag": "remote-dns" },
      { "address": "8.8.8.8", "domains": ["full:engage.cloudflareclient.com"], "skipFallback": true }
    ],
    "queryStrategy": "UseIPv4",
    "tag": "dns"
  },
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "mixed",
      "settings": { "auth": "noauth", "udp": true },
      "sniffing": { "destOverride": ["http", "tls"], "enabled": true, "routeOnly": true },
      "tag": "mixed-in"
    },
    {
      "listen": "127.0.0.1",
      "port": 10853,
      "protocol": "dokodemo-door",
      "settings": { "address": "1.1.1.1", "network": "tcp,udp", "port": 53 },
      "tag": "dns-in"
    }
  ],
  "outbounds": [
    {
      "protocol": "wireguard",
      "settings": {
        "address": ["172.16.0.2/32", "2606:4700:110:8d8c:1fb8:8912:5721:fbf4/128"],
        "mtu": 1280,
        "peers": [{
          "endpoint": "[2606:4700:d0:0:ea54:770d:987d:a1a9]:500",
          "publicKey": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
          "keepAlive": 5
        }],
        "reserved": [91, 58, 183],
        "secretKey": "vzlpyof1lWI4z8pEVy1dnURyxJXFraff+MVDaq7G0qc="
      },
      "streamSettings": {
        "finalmask": {
          "udp": [{
            "type": "noise",
            "settings": {
              "reset": "30-60",
              "noise": [
                { "rand": "50-100", "randRange": "0-255", "delay": "1-5" },
                { "rand": "50-100", "randRange": "0-255", "delay": "1-5" },
                { "rand": "50-100", "randRange": "0-255", "delay": "1-5" },
                { "rand": "50-100", "randRange": "0-255", "delay": "1-5" },
                { "rand": "50-100", "randRange": "0-255", "delay": "1-5" }
              ]
            }
          }]
        }
      },
      "tag": "proxy"
    },
    { "protocol": "dns", "settings": { "rules": [{ "action": "hijack" }] }, "tag": "dns-out" },
    { "protocol": "freedom", "settings": { "domainStrategy": "UseIP" }, "tag": "direct" },
    { "protocol": "blackhole", "settings": { "response": { "type": "http" } }, "tag": "block" }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "inboundTag": ["mixed-in"], "port": 53, "outboundTag": "dns-out", "type": "field" },
      { "inboundTag": ["dns-in"], "outboundTag": "dns-out", "type": "field" },
      { "inboundTag": ["remote-dns"], "outboundTag": "proxy", "type": "field" },
      { "inboundTag": ["dns"], "outboundTag": "direct", "type": "field" },
      { "domain": ["geosite:private"], "outboundTag": "direct", "type": "field" },
      { "ip": ["geoip:private"], "outboundTag": "direct", "type": "field" },
      { "network": "tcp,udp", "outboundTag": "proxy", "type": "field" }
    ]
  },
  "policy": {
    "levels": { "0": { "connIdle": 300, "handshake": 4, "uplinkOnly": 1, "downlinkOnly": 1 } },
    "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true }
  },
  "stats": {}
}
"""

    private suspend fun registerMasqueThroughProxy(
        service: Service,
        targetGuid: String,
        targetProfile: ProfileItem,
        proxyGuid: String,
    ) {
        val proxyProfile = MmkvManager.decodeServerConfig(proxyGuid)
            ?: throw IllegalStateException("Selected Proxy configuration no longer exists")
        requireUsable(proxyProfile)

        val previousGuid = MmkvManager.getSelectServer()
        val previousServiceControl = CoreServiceManager.serviceControl?.get()
        val host = when (service) {
            is com.v2ray.ang.service.CoreVpnService -> WarpMasqueRegistrationExecutionPolicy.Host.VPN
            is com.v2ray.ang.service.CoreRootService -> WarpMasqueRegistrationExecutionPolicy.Host.ROOT
            is com.v2ray.ang.service.CoreProxyOnlyService -> WarpMasqueRegistrationExecutionPolicy.Host.PROXY_ONLY
            else -> WarpMasqueRegistrationExecutionPolicy.Host.OTHER
        }
        val execution = WarpMasqueRegistrationExecutionPolicy.executionFor(host)
        val registrationProxyGuid = proxyGuid
        var proxyOnlyStartRequested = false
        var currentServiceCoreStarted = false
        try {
            if (CoreServiceManager.isRunning()) {
                val runningService = CoreServiceManager.serviceControl?.get()?.getService() ?: service
                CoreServiceManager.stopCoreLoop(runningService)
                waitForDaemonCore(false, service)
            }
            checkStartupActive(service)
            MmkvManager.setSelectServer(registrationProxyGuid)
            PingNgDiagnostics.record("Starting MASQUE registration through Proxy profile $registrationProxyGuid")
            when (execution) {
                WarpMasqueRegistrationExecutionPolicy.Execution.TEMPORARY_PROXY_ONLY_SERVICE -> {
                    proxyOnlyStartRequested = true
                    LauncherManager.startProxyOnlyService(service.applicationContext, registrationProxyGuid)
                }

                WarpMasqueRegistrationExecutionPolicy.Execution.CURRENT_SERVICE -> {
                    check(CoreServiceManager.startCoreLoop(null)) {
                        "Could not start the selected MASQUE registration Proxy profile"
                    }
                    currentServiceCoreStarted = true
                }
            }
            waitForDaemonCore(true, service)
            val socksPort = if (proxyProfile.warpRegistrationInternalProxy == true) {
                BUILTIN_PROXY_SOCKS_PORT
            } else {
                SettingsManager.getSocksPort()
            }
            waitForSocks(socksPort, service)
            checkStartupActive(service)
            PingNgDiagnostics.record("MASQUE registration Proxy SOCKS listener is ready on port $socksPort")
            if (!hasMasqueCredentials(targetProfile) || targetProfile.warpMasqueRegistrationGuid != targetGuid) {
                targetProfile.warpMasqueConfigJson = null
                if (targetProfile.warpMasqueRegistrationGuid != targetGuid)
                    File(service.filesDir, "warp-masque/$targetGuid.json").delete()
                check(WarpMasqueBridge.registerWithProxy(service, targetGuid, targetProfile, socksPort)) {
                    "WARP MASQUE registration through Proxy failed"
                }
                targetProfile.warpMasqueRegistrationGuid = targetGuid
                MmkvManager.encodeServerConfig(targetGuid, targetProfile)
            }
            if (WarpMasqueConfig.shouldAutoScan(targetProfile) &&
                !EndpointScannerCli.hasMasqueH2Account(service, targetGuid)) {
                EndpointScannerCli.prepareMasqueH2Account(service, targetGuid, socksPort)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    if (proxyOnlyStartRequested) {
                        LauncherManager.stopProxyOnlyService(
                            service.applicationContext,
                            registrationProxyGuid,
                        )
                        waitForDaemonCore(false, service, checkCancellation = false)
                    } else if (currentServiceCoreStarted || CoreServiceManager.isRunning()) {
                        val runningService = CoreServiceManager.serviceControl?.get()?.getService() ?: service
                        CoreServiceManager.stopCoreLoop(runningService)
                        waitForDaemonCore(false, service, checkCancellation = false)
                    }
                } finally {
                    previousGuid?.let(MmkvManager::setSelectServer)
                    if (execution == WarpMasqueRegistrationExecutionPolicy.Execution.TEMPORARY_PROXY_ONLY_SERVICE) {
                        val restoreControl = previousServiceControl ?: (service as? ServiceControl)
                        if (restoreControl != null) {
                            CoreServiceManager.serviceControl = SoftReference(restoreControl)
                        }
                    }
                }
            }
        }
    }

    private fun hasMasqueCredentials(profile: ProfileItem): Boolean = runCatching {
        val config = profile.warpMasqueConfigJson?.takeIf(String::isNotBlank)
            ?: return@runCatching false
        val root = JsonUtil.parseString(config)?.asJsonObject
            ?: return@runCatching false
        listOf("private_key", "access_token", "id").all { key ->
            root.get(key)?.asString?.isNotBlank() == true
        }
    }.getOrDefault(false)

    suspend fun register(
        context: Context,
        proxyGuid: String,
    ): WarpAccount = registrationMutex.withLock {
        withContext(Dispatchers.IO) {
            val selectedProxy = normalizeSelection(proxyGuid)
            if (isAutoChoice(selectedProxy)) {
                PingNgDiagnostics.record("Using Auto: trying WARP key registration directly before Proxy-1 and Proxy-2")
                val directResult = runCatching { WarpAccountGenerator.register() }
                if (directResult.isSuccess) {
                    PingNgDiagnostics.record("Auto WARP key registration completed directly")
                    return@withContext directResult.getOrThrow()
                }
                val directFailure = directResult.exceptionOrNull()
                    ?: IllegalStateException("Direct WARP registration failed")
                if (directFailure is CancellationException) throw directFailure
                PingNgDiagnostics.record("Direct WARP key registration failed; retrying through Proxy-1", directFailure)
                var proxy1Failure: Throwable? = null
                try {
                    return@withContext withBuiltinProxyProfile(context, PROXY_1) { proxy ->
                        WarpAccountGenerator.register(proxy)
                    }.also { PingNgDiagnostics.record("WARP key registration completed through Proxy-1") }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    proxy1Failure = error
                    PingNgDiagnostics.record("Proxy-1 WARP key registration failed; retrying through Proxy-2", error)
                }
                try {
                    return@withContext withBuiltinProxyProfile(context, PROXY_2) { proxy ->
                        WarpAccountGenerator.register(proxy)
                    }.also { PingNgDiagnostics.record("WARP key registration completed through Proxy-2") }
                } catch (proxy2Failure: Throwable) {
                    if (proxy2Failure is CancellationException) throw proxy2Failure
                    throw IllegalStateException(
                        "Direct, Proxy-1, and Proxy-2 WARP key registration attempts failed: " +
                            (proxy2Failure.message ?: proxy2Failure.javaClass.simpleName),
                        proxy2Failure,
                    ).also {
                        it.addSuppressed(directFailure)
                        proxy1Failure?.let(it::addSuppressed)
                    }
                }
            }
            if (isProxy1Choice(selectedProxy)) {
                PingNgDiagnostics.record("Using Proxy-1 for WARP key registration")
                return@withContext withBuiltinProxyProfile(context, PROXY_1) { proxy ->
                    WarpAccountGenerator.register(proxy)
                }
            }
            if (isProxy2Choice(selectedProxy)) {
                PingNgDiagnostics.record("Using Proxy-2 for WARP key registration")
                return@withContext withBuiltinProxyProfile(context, PROXY_2) { proxy ->
                    WarpAccountGenerator.register(proxy)
                }
            }
            if (selectedProxy != AUTO) {
                val profile = MmkvManager.decodeServerConfig(selectedProxy)
                    ?: throw IllegalStateException("Selected Proxy configuration no longer exists")
                requireUsable(profile)
                return@withContext withProxyProfile(context, selectedProxy) { proxy ->
                    WarpAccountGenerator.register(proxy)
                }
            }
            throw IllegalStateException("Unknown WARP registration path")
        }
    }

    private suspend fun <T> withProxyProfile(
        context: Context,
        guid: String,
        action: suspend (Proxy) -> T,
    ): T {
        val app = context.applicationContext
        val previousGuid = MmkvManager.getSelectServer()
        val wasRunning = MmkvManager.isCoreServiceRunning()
        try {
            LauncherManager.stopService(app)
            waitForCoreService(shouldRun = false)
            LauncherManager.startProxyOnlyService(app, guid)
            waitForCoreService(shouldRun = true)
            val port = if (guid == PROXY_1 || guid == PROXY_2) {
                BUILTIN_PROXY_SOCKS_PORT
            } else {
                SettingsManager.getSocksPort()
            }
            waitForSocks(port)
            return action(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } finally {
            withContext(NonCancellable) {
                LauncherManager.stopService(app)
                waitForCoreService(shouldRun = false)
                previousGuid?.let(MmkvManager::setSelectServer)
                if (wasRunning && previousGuid != null) {
                    LauncherManager.startService(app, previousGuid)
                }
            }
        }
    }

    private fun requireUsable(profile: ProfileItem) {
        if (profile.configType == EConfigType.WARP && !hasMasqueCredentials(profile)) {
            throw IllegalStateException("Selected WARP Proxy has no registered credentials")
        }
        if (profile.configType == EConfigType.WIREGUARD &&
            (profile.secretKey.isNullOrBlank() || profile.publicKey.isNullOrBlank())
        ) {
            throw IllegalStateException("Selected WireGuard Proxy has no key pair")
        }
    }

    private suspend fun waitForCoreService(shouldRun: Boolean) {
        repeat(300) {
            if (MmkvManager.isCoreServiceRunning() == shouldRun) return
            delay(100)
        }
    }

    private suspend fun waitForDaemonCore(
        shouldRun: Boolean,
        service: Service,
        checkCancellation: Boolean = true,
    ) {
        repeat(300) {
            if (checkCancellation) checkStartupActive(service)
            if (CoreServiceManager.isRunning() == shouldRun) return
            delay(100)
        }
        check(CoreServiceManager.isRunning() == shouldRun) {
            if (shouldRun) "Selected MASQUE registration Proxy did not start its core in time"
            else "MASQUE registration Proxy core did not stop in time"
        }
    }

    private suspend fun waitForSocks(port: Int, service: Service) {
        repeat(300) {
            checkStartupActive(service)
            val ready = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }
            }.isSuccess
            if (ready) return
            delay(100)
        }
        throw IllegalStateException("Selected MASQUE registration Proxy did not start its local SOCKS listener")
    }

    private fun checkStartupActive(service: Service) {
        val cancelled = when (service) {
            is com.v2ray.ang.service.CoreVpnService -> service.isStartupCancelled()
            is com.v2ray.ang.service.CoreProxyOnlyService -> service.isStartupCancelled()
            is com.v2ray.ang.service.CoreRootService -> service.isStartupCancelled()
            else -> false
        }
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("WARP MASQUE registration canceled")
        }
    }

    private suspend fun waitForSocks(port: Int) {
        repeat(300) {
            val ready = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }
            }.isSuccess
            if (ready) return
            delay(100)
        }
        throw IllegalStateException("Selected registration Proxy did not start its local SOCKS listener")
    }
}
