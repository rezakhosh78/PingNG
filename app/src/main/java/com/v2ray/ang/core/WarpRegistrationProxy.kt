package com.v2ray.ang.core

import android.content.Context
import android.app.Service
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import java.io.File
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

    fun prepareSelectedMasqueRegistration(service: Service) {
        val guid = MmkvManager.getSelectServer() ?: return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (profile.configType != EConfigType.WARP) return
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
        return listOf(Choice(BUNDLED_PROXY_GUID, PUBLIC_PROXY_REMARK)) + choices
    }

    /** Registers a MASQUE profile before TUN setup, with the saved or automatic Proxy choice. */
    fun prepareMasqueRegistration(
        service: Service,
        targetGuid: String,
        targetProfile: ProfileItem,
        proxyGuid: String,
    ) {
        if (hasMasqueCredentials(targetProfile)) return

        if (proxyGuid == AUTO) {
            runCatching { WarpMasqueBridge.prepareRegistration(service, targetGuid, targetProfile) }
                .onSuccess { return }
                .onFailure { directFailure ->
                    if (directFailure is CancellationException) throw directFailure
                    targetProfile.warpMasqueConfigJson = null
                    runCatching {
                        registerMasqueThroughProxy(
                            service,
                            targetGuid,
                            targetProfile,
                            BUNDLED_PROXY_GUID,
                        )
                    }.onFailure { proxyFailure ->
                        if (proxyFailure is CancellationException) throw proxyFailure
                        throw IllegalStateException(
                            "WARP MASQUE registration failed directly and through Proxy-1: " +
                                (proxyFailure.message ?: proxyFailure.javaClass.simpleName),
                            proxyFailure,
                        ).also { it.addSuppressed(directFailure) }
                    }
                }
            return
        }

        targetProfile.warpMasqueConfigJson = null
        registerMasqueThroughProxy(service, targetGuid, targetProfile, proxyGuid)
    }

    private fun registerMasqueThroughProxy(
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
        var proxyCoreStarted = false
        if (bundled) MmkvManager.encodeEphemeralServerConfig(INTERNAL_PROFILE_GUID, proxyProfile)
        try {
            if (CoreServiceManager.isRunning()) {
                CoreServiceManager.stopCoreLoop(service)
                waitForCoreServiceBlocking(false, service)
            }
            checkStartupActive(service)
            MmkvManager.setSelectServer(if (bundled) INTERNAL_PROFILE_GUID else proxyGuid)
            check(CoreServiceManager.startCoreLoop(null)) {
                "Could not start the selected Proxy profile"
            }
            proxyCoreStarted = true
            val socksPort = SettingsManager.getSocksPort()
            waitForSocksBlocking(socksPort, service)
            checkStartupActive(service)
            check(WarpMasqueBridge.registerWithProxy(service, targetGuid, targetProfile, socksPort)) {
                "WARP MASQUE registration through Proxy failed"
            }
        } finally {
            try {
                if (proxyCoreStarted || CoreServiceManager.isRunning()) {
                    CoreServiceManager.stopCoreLoop(service)
                    waitForCoreServiceBlocking(false, service, checkCancellation = false)
                }
            } finally {
                previousGuid?.let(MmkvManager::setSelectServer)
                if (bundled) {
                    MmkvManager.removeEphemeralServerConfig(INTERNAL_PROFILE_GUID)
                }
            }
        }
    }

    private fun hasMasqueCredentials(profile: ProfileItem): Boolean = runCatching {
        val root = JsonUtil.parseString(profile.warpMasqueConfigJson.orEmpty())?.asJsonObject
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
            profile.server == "104.17.71.206" && profile.serverPort == "443" &&
                profile.network == "ws" && profile.security == "tls" &&
                profile.path == "/vl/VhUzrJSK2tMhgryGTPJqfR?ed=2560" &&
                profile.sni.equals("n5YmPfFTwt17MZQjB9tfv.WOUDhB.workERs.dEV", ignoreCase = true)
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
        if (profile.configType == EConfigType.WARP &&
            profile.warpMasqueConfigJson?.let { it.contains("private_key") && it.contains("access_token") } != true
        ) {
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

    private fun waitForCoreServiceBlocking(
        shouldRun: Boolean,
        service: Service,
        checkCancellation: Boolean = true,
    ) {
        repeat(300) {
            if (checkCancellation) checkStartupActive(service)
            if (MmkvManager.isCoreServiceRunning() == shouldRun) return
            Thread.sleep(100)
        }
        check(MmkvManager.isCoreServiceRunning() == shouldRun) {
            "Proxy service did not stop in time"
        }
    }

    private fun waitForSocksBlocking(port: Int, service: Service) {
        repeat(300) {
            checkStartupActive(service)
            val ready = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }
            }.isSuccess
            if (ready) return
            Thread.sleep(100)
        }
        throw IllegalStateException("Selected Proxy did not start its local SOCKS listener")
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
