package com.v2ray.ang.core

import android.content.Context
import android.app.Service
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.VlessFmt
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

/** Routes WARP registration through an existing PingNG profile when direct access is filtered. */
object WarpRegistrationProxy {
    const val AUTO = "__AUTO__"
    const val AUTO_LABEL = "Auto"
    const val PUBLIC_PROXY_REMARK = "Proxy-1"
    const val BUNDLED_PROXY_GUID = "__PINGNG_PROXY_1_VLESS__"
    private const val INTERNAL_PROFILE_GUID = "pingng-internal-warp-registration-proxy-1"
    /** Proxy startup and cleanup mutate process-wide selected-profile state. */
    private val registrationMutex = Mutex()

    data class Choice(val guid: String, val label: String)

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
            proxyGuid = profile.warpRegistrationProxyGuid ?: AUTO,
        )
    }

    /** Removes the old bundled MASQUE Proxy-1 and migrates saved WARP proxy selections. */
    fun removeLegacyMasqueProxy1() {
        val legacyGuids = MmkvManager.decodeAllServerList().filter { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@filter false
            profile.configType == EConfigType.WARP &&
                profile.remarks.trim() == PUBLIC_PROXY_REMARK &&
                profile.server == "162.159.198.7" &&
                profile.serverPort == "443" &&
                profile.warpMasqueSni.equals("Soft98.ir", ignoreCase = true)
        }.toMutableSet()
        if (legacyGuids.isEmpty()) return

        MmkvManager.decodeAllServerList().forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            if (profile.warpRegistrationProxyGuid?.let { it in legacyGuids } == true) {
                profile.warpRegistrationProxyGuid = AUTO
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
            (listOf(Choice(BUNDLED_PROXY_GUID, PUBLIC_PROXY_REMARK)) + choices).map {
                WarpProxyChoiceLabel(it.guid, it.label)
            },
            reservedLabels = setOf(AUTO_LABEL),
        ).map { Choice(it.guid, it.label) }
    }

    /** Registers a MASQUE profile before TUN setup, with the saved or automatic Proxy choice. */
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

            if (proxyGuid == AUTO) {
                val directFailure = try {
                    if (!hasMasqueCredentials(targetProfile) || targetProfile.warpMasqueRegistrationGuid != targetGuid) {
                        targetProfile.warpMasqueConfigJson = null
                        if (targetProfile.warpMasqueRegistrationGuid != targetGuid)
                            File(service.filesDir, "warp-masque/$targetGuid.json").delete()
                        WarpMasqueBridge.prepareRegistration(service, targetGuid, targetProfile)
                        targetProfile.warpMasqueRegistrationGuid = targetGuid
                        MmkvManager.encodeServerConfig(targetGuid, targetProfile)
                    }
                    if (scannerMissing()) EndpointScannerCli.prepareMasqueH2Account(service, targetGuid)
                    return@withContext
                } catch (error: Throwable) {
                    if (!WarpMasqueRegistrationFallbackPolicy.shouldFallbackToProxy1(
                            autoSelected = true,
                            directAttemptFailed = true,
                            canceled = error is CancellationException,
                        )
                    ) throw error
                    PingNgDiagnostics.record(
                        "Direct MASQUE registration failed; automatically retrying through Proxy-1",
                        error,
                    )
                    error
                }
                try {
                    registerMasqueThroughProxy(
                        service,
                        targetGuid,
                        targetProfile,
                        BUNDLED_PROXY_GUID,
                    )
                } catch (proxyFailure: Throwable) {
                    if (proxyFailure is CancellationException) throw proxyFailure
                    throw IllegalStateException(
                        "WARP MASQUE registration failed directly and through Proxy-1: " +
                            (proxyFailure.message ?: proxyFailure.javaClass.simpleName),
                        proxyFailure,
                    ).also { it.addSuppressed(directFailure) }
                }
                return@withContext
            }

            registerMasqueThroughProxy(service, targetGuid, targetProfile, proxyGuid)
        }
    }

    private suspend fun registerMasqueThroughProxy(
        service: Service,
        targetGuid: String,
        targetProfile: ProfileItem,
        proxyGuid: String,
    ) {
        val bundled = proxyGuid == BUNDLED_PROXY_GUID
        val proxyProfile = if (bundled) {
            loadBundledProxyProfile(service).apply { warpRegistrationInternalProxy = true }
        } else {
            MmkvManager.decodeServerConfig(proxyGuid)
                ?: throw IllegalStateException("Selected Proxy configuration no longer exists")
        }
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
        val registrationProxyGuid = if (bundled) INTERNAL_PROFILE_GUID else proxyGuid
        var proxyOnlyStartRequested = false
        var currentServiceCoreStarted = false
        if (bundled) MmkvManager.encodeEphemeralServerConfig(INTERNAL_PROFILE_GUID, proxyProfile)
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
            val socksPort = SettingsManager.getSocksPort()
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
                    if (bundled) {
                        MmkvManager.removeEphemeralServerConfig(INTERNAL_PROFILE_GUID)
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
        excludeGuid: String,
    ): WarpAccount = registrationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (proxyGuid == BUNDLED_PROXY_GUID) {
                val proxyProfile = loadBundledProxyProfile(context)
                MmkvManager.encodeEphemeralServerConfig(INTERNAL_PROFILE_GUID, proxyProfile)
                try {
                    return@withContext withProxyProfile(context, INTERNAL_PROFILE_GUID) { proxy ->
                        WarpAccountGenerator.register(proxy)
                    }
                } finally {
                    withContext(NonCancellable) {
                        MmkvManager.removeEphemeralServerConfig(INTERNAL_PROFILE_GUID)
                        runCatching {
                            File(context.applicationContext.filesDir, "warp-masque/$INTERNAL_PROFILE_GUID.json").delete()
                        }
                    }
                }
            }
            if (proxyGuid != AUTO) {
                val profile = MmkvManager.decodeServerConfig(proxyGuid)
                    ?: throw IllegalStateException("Selected Proxy configuration no longer exists")
                requireUsable(profile)
                return@withContext withProxyProfile(context, proxyGuid) { proxy ->
                    WarpAccountGenerator.register(proxy)
                }
            }

            val directResult = runCatching { WarpAccountGenerator.register() }
            if (directResult.isSuccess) return@withContext directResult.getOrThrow()
            val directFailure = directResult.exceptionOrNull()
                ?: IllegalStateException("Direct WARP registration failed")
            if (directFailure is CancellationException) throw directFailure

            val proxyProfile = loadBundledProxyProfile(context)
            MmkvManager.encodeEphemeralServerConfig(INTERNAL_PROFILE_GUID, proxyProfile)
            try {
                withProxyProfile(context, INTERNAL_PROFILE_GUID) { proxy ->
                    WarpAccountGenerator.register(proxy)
                }
            } catch (proxyFailure: Throwable) {
                if (proxyFailure is CancellationException) throw proxyFailure
                throw IllegalStateException(
                    "WARP key registration failed directly and through the bundled Proxy-1 profile: " +
                        (proxyFailure.message ?: proxyFailure.javaClass.simpleName),
                    proxyFailure,
                ).also { it.addSuppressed(directFailure) }
            } finally {
                withContext(NonCancellable) {
                    MmkvManager.removeEphemeralServerConfig(INTERNAL_PROFILE_GUID)
                    runCatching {
                        File(context.applicationContext.filesDir, "warp-masque/$INTERNAL_PROFILE_GUID.json").delete()
                    }
                }
            }
        }
    }

    private fun loadBundledProxyProfile(context: Context): ProfileItem {
        val uri = context.resources.openRawResource(R.raw.warp_registration_proxy_1)
            .bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        val profile = runCatching { VlessFmt.parse(uri) }.getOrNull()
            ?: throw IllegalStateException("Bundled VLESS registration Proxy-1 profile is invalid")
        check(profile.configType == EConfigType.VLESS) { "Bundled Proxy-1 must use VLESS" }
        check(
            profile.password == "667ef0e4-2a1f-479e-a924-6138fae98401" &&
                profile.server == "pingng.novintech.site" && profile.serverPort == "443" &&
                profile.network == "ws" && profile.security == "tls" &&
                profile.path == "/vl/VhUzrJSK2tMhgryGTPJqfR?ed=2560" &&
                profile.host == "n5ympfftwt17mzqjb9tfv.woudhb.workers.dev" &&
                profile.sni.equals("n5YmPfFTwt17MZQjB9tfv.WOUDhB.workERs.dEV", ignoreCase = true) &&
                profile.alpn == "http/1.1" && profile.fingerPrint == "chrome"
        ) { "Bundled VLESS registration Proxy-1 settings are incomplete" }
        return profile.apply {
            remarks = PUBLIC_PROXY_REMARK
            description = "Internal VLESS WebSocket/TLS registration proxy"
            warpRegistrationInternalProxy = true
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
            val port = SettingsManager.getSocksPort()
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
        throw IllegalStateException("Proxy-1 VLESS did not start its local SOCKS listener")
    }
}
