package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.AmneziaWgVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import org.amnezia.awg.GoBackend
import java.lang.ref.SoftReference
import java.net.InetAddress
import java.net.InetSocketAddress

object CoreServiceManager {

    // Creating a gomobile controller loads libgojni.so. AmneziaWG has its own
    // Go runtime and must not eagerly load Xray merely to inspect VPN state.
    private val coreControllerDelegate = lazy {
        CoreNativeManager.newCoreController(CoreCallback())
    }
    private val coreController: CoreController by coreControllerDelegate
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    @Volatile
    private var currentConfigGuid: String? = null
    private var autoExitProbeJob: Job? = null
    /** Bounds automatic endpoint recovery within a single VPN session. */
    private var awgFailoverCount = 0
    private var probeScope: CoroutineScope? = null
    private var coreStopJob: Job? = null
    @Volatile
    private var stopping = false
    @Volatile
    private var lifecycleGeneration = 0L
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    /** Native handle for the standalone AmneziaWG Go engine; -1 when stopped. */
    @Volatile
    private var amneziaWgHandle: Int = -1

    /** OpenVPN 3 runs as a standalone VPN client and does not start the Xray core. */
    @Volatile
    private var openVpnActive = false

    /**
     * Duplicate kept only for Android lifecycle/reload bookkeeping. The native engine receives
     * the original VPN descriptor, just like the official AmneziaWG Android backend.
     */
    private var amneziaWgLifecycleTun: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
            if (service != null && service !is AmneziaWgVpnService &&
                selected?.configType != EConfigType.AMNEZIAWG &&
                selected?.configType != EConfigType.OPENVPN) {
                initializeXray(service)
            } else if (service != null) {
                PingNgDiagnostics.record("Standalone VPN runtime: Xray initialization skipped")
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = amneziaWgHandle >= 0 ||
        (coreControllerDelegate.isInitialized() && coreController.isRunning)

    /** True while the owned service is tearing down its current VPN/core session. */
    fun isStopping(): Boolean = stopping

    /** The notification may say Connected only while the selected route is actually usable. */
    fun isNotificationConnected(): Boolean {
        if (stopping) return false
        if (openVpnActive) return true

        val config = currentConfig ?: return false
        if (!isRunning()) return false
        if (config.psiphonEnabled && !PsiphonBridge.isEgressVerified()) return false
        if (config.torEnabled && !TorBridge.isConnected()) return false

        val service = getService()
        if (service is CoreVpnService && !service.hasEstablishedVpnInterfaceForProbe()) return false
        return true
    }

    /** Checks the duplicate descriptor retained after AWG takes ownership of the original TUN. */
    fun hasEstablishedAmneziaWgTun(): Boolean {
        if (currentConfig?.configType != EConfigType.AMNEZIAWG || amneziaWgHandle < 0) return false
        val tun = currentVpnInterface ?: amneziaWgLifecycleTun ?: return false
        return runCatching { tun.fileDescriptor.valid() }.getOrDefault(false)
    }

    /** State query for UI/tile clients that also represent standalone VPN engines. */
    fun isAnyVpnRuntimeRunning(): Boolean = openVpnActive || isRunning()

    /** True only after OpenVPN 3 reports CONNECTED and until its tunnel is torn down. */
    fun isOpenVpnActive(): Boolean = openVpnActive

    /** Keeps standalone OpenVPN probe sockets outside this app's active VPN interface. */
    fun protectOpenVpnProbeSocket(socket: Int, profileGuid: String): Boolean {
        val control = serviceControl?.get() ?: return true
        val service = try {
            control.getService()
        } catch (error: Throwable) {
            LogUtil.e(
                AppConfig.TAG,
                "OpenVPN real-delay service lookup failed profile=${profileGuid.take(8)}",
                error,
            )
            return false
        }
        val vpnService = service as? CoreVpnService ?: return true
        if (!vpnService.hasEstablishedVpnInterfaceForProbe()) return true
        return runCatching { control.vpnProtect(socket) }
            .onFailure {
                LogUtil.e(
                    AppConfig.TAG,
                    "OpenVPN real-delay socket protection failed profile=${profileGuid.take(8)}",
                    it,
                )
            }
            .getOrElse { false }
            .also { protected ->
                if (!protected) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "OpenVPN real-delay socket could not bypass the active VPN profile=${profileGuid.take(8)}",
                    )
                }
            }
    }

    fun setOpenVpnActive(active: Boolean) {
        openVpnActive = active
    }

    /** Registers service control and connection-test messages for standalone OpenVPN. */
    fun registerOpenVpnServiceMessages(service: Service) {
        stopping = false
        registerServiceMessages(service)
    }

    /** Releases the receiver registered for a standalone OpenVPN session. */
    fun unregisterOpenVpnServiceMessages(service: Service) {
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(mMsgReceive) }
                .onFailure { LogUtil.w(AppConfig.TAG, "OpenVPN receiver unregister failed", it) }
            receiverRegistered = false
        }
    }

    private fun initializeXray(service: Service) {
        check(service !is AmneziaWgVpnService) {
            "Xray cannot be loaded in the isolated AmneziaWG process"
        }
        CoreNativeManager.initCoreEnv(service)
        if (processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            processFinder = XrayProcessFinder(service)
            coreController.registerProcessFinder(processFinder)
        }
    }

    /** Rebuilds only the native Desync listener; Xray and VPN stay untouched. */
    fun reconfigureDesync(): Boolean {
        val service = getService()
        if (service !is CoreProxyOnlyService || !isRunning()) return false
        val guid = MmkvManager.getSelectServer() ?: return false
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        return runCatching {
            PingNgDesyncManager.reconfigure(profile) > 0
        }.onFailure {
            PingNgDiagnostics.record("Desync candidate reconfigure failed", it)
        }.getOrDefault(false)
    }

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        // Native stopLoop can outlive the Android service that requested it. A new WARP/
        // WireGuard instance must wait until the previous native instance released its listeners
        // and TUN resources, otherwise reconnects race with the old core.
        coreStopJob?.let { stopJob ->
            if (stopJob.isActive) {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: waiting for previous core stop")
                runBlocking { stopJob.join() }
            }
            coreStopJob = null
        }

        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Throwable) {
            BepassBridge.stop(service, force = true)
            PsiphonBridge.stop()
            TorBridge.stop()
            stopAmneziaWgEngine()
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            PingNgDesyncManager.stop()
            WarpMasqueBridge.stop()
            MasterDnsBridge.stop()
            SoarBridge.stop()
            WarpMasqueDesyncProxy.stop()
            autoExitProbeJob?.cancel()
            autoExitProbeJob = null
            probeScope?.cancel()
            probeScope = null
            networkMonitor?.unregister()
            networkMonitor = null
            if (receiverRegistered) {
                runCatching { service.unregisterReceiver(mMsgReceive) }
                receiverRegistered = false
            }
            if (coreControllerDelegate.isInitialized()) {
                runCatching { CoreNativeManager.reconcileBrowserDialer("") }
                browserDialer?.stop()
                browserDialer = null
                if (runCatching { coreController.isRunning }.getOrDefault(false)) {
                    val controller = coreController
                    coreStopJob = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            controller.stopLoop()
                            PingNgDiagnostics.record("Partial core stopped after startup failure")
                        } catch (stopError: Throwable) {
                            PingNgDiagnostics.record("Partial core cleanup failed", stopError)
                        } finally {
                            MmkvManager.setCoreServiceStatus(false)
                        }
                    }
                }
            }
            currentVpnInterface = null
            currentConfig = null
            currentConfigGuid = null
            MmkvManager.setCoreServiceStatus(false)
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            PingNgDiagnostics.record("Core start failed: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        stopping = false
        awgFailoverCount = 0
        lifecycleGeneration += 1
        registerServiceMessages(service)

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    private fun registerServiceMessages(service: Service) {
        if (receiverRegistered) return
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(service, mMsgReceive, filter, Utils.receiverFlags())
        receiverRegistered = true
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        PingNgDiagnostics.record("Start requested for ${config.remarks} (${config.configType})")
        NotificationManager.showNotification(null)
        if (WarpWireGuardConfig.isProfile(config)) {
            PingNgDiagnostics.record(
                "WARP WireGuard runtime endpoint: ${config.server.orEmpty()}:${config.serverPort.orEmpty()}"
            )
        }
        if (config.configType == EConfigType.AMNEZIAWG) {
            startAmneziaWg(service, guid, config, vpnInterface, isReload)
            return
        }
        BepassBridge.start(service, guid, config, forceRestart = isReload)
        initializeXray(service)
        if (!isReload) {
            PingNgDesyncManager.start(config)
            if (PingNgCompat.isNativeDesyncEnabled(config)) {
                check(PingNgDesyncManager.activePort() in 1..65535) {
                    "Desync was enabled but did not start"
                }
            }
        }
        val masqueFinalMaskNeedsXray = WarpMasqueFinalMaskConfig.isEnabled(config)
        if (config.configType == com.v2ray.ang.enums.EConfigType.WARP) {
            if (PingNgCompat.isNativeDesyncEnabled(config)) {
                // The local HTTP CONNECT adapter bridges MASQUE's outer H2
                // connection into the native Desync SOCKS listener. Start it
                // before the external MASQUE process reads its config.
                WarpMasqueDesyncProxy.start(PingNgDesyncManager.activePort())
            } else {
                WarpMasqueDesyncProxy.stop()
            }
            // FinalMask needs Xray's loopback HTTP bridge for MASQUE's outer
            // TLS socket, so that profile starts after Xray opens the bridge.
            if (!masqueFinalMaskNeedsXray) WarpMasqueBridge.startIfNeeded(service, guid, config)
        }
        if (MasterDnsBridge.isProfile(config)) {
            MasterDnsBridge.start(service, guid, config)
        }
        if (SoarBridge.isProfile(config)) {
            SoarBridge.start(
                service,
                guid,
                config,
                waitForHealthyResolver = true,
                cancellationRequested = {
                    when (service) {
                        is CoreVpnService -> service.isStartupCancelled()
                        is CoreRootService -> service.isStartupCancelled()
                        is CoreProxyOnlyService -> service.isStartupCancelled()
                        else -> false
                    }
                }
            )
        }
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (config.warpRegistrationInternalProxy == true) {
            LogUtil.d(AppConfig.TAG, "Internal WARP registration proxy config generated (content redacted)")
        } else {
            LogUtil.d(AppConfig.TAG, result.content)
        }
        if (!result.status) {
            if (!isReload) PingNgDesyncManager.stop()
            WarpMasqueBridge.stop()
            MasterDnsBridge.stop()
            SoarBridge.stop()
            WarpMasqueDesyncProxy.stop()
            PingNgDiagnostics.record("Xray configuration generation failed: ${result.errorMessage}")
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        currentConfigGuid = guid
        val waitingForSecondLayerVpn = !isReload &&
            service is CoreVpnService &&
            vpnInterface == null &&
            (config.psiphonEnabled || config.torEnabled)
        var preparedConfig = WarpMasqueFinalMaskConfig.prepare(config, result.content)
        preparedConfig = BepassBridge.prepareCoreConfig(guid, config, preparedConfig)
        preparedConfig = if (config.torEnabled) {
            TorBridge.prepareCoreConfig(config, preparedConfig)
        } else {
            PsiphonBridge.prepareCoreConfig(service, config, preparedConfig)
        }
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        } else if (config.configType == com.v2ray.ang.enums.EConfigType.WARP &&
            WarpMasqueConfig.isDescription(config.description) &&
            vpnInterface != null
        ) {
            PingNgDiagnostics.record(
                "WARP MASQUE using Xray native TUN; Android VPN package filters select routed apps",
            )
        }

        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(preparedConfig, tunFd)
        check(isRunning()) { "Core failed to enter the running state" }
        if (masqueFinalMaskNeedsXray) {
            try {
                WarpMasqueBridge.startIfNeeded(service, guid, config)
            } catch (error: Throwable) {
                runCatching { coreController.stopLoop() }
                throw error
            }
        }
        MmkvManager.setCoreServiceStatus(true)
        PingNgDiagnostics.record("Xray core is running")
        NotificationManager.showNotification(currentConfig)
        resetProbeScope()

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            if (waitingForSecondLayerVpn) {
                PingNgDiagnostics.record("Xray bootstrap running; VPN held until the selected second layer connects")
            } else {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            }
            PsiphonBridge.startAfterCoreConnected(service, guid, config)
            TorBridge.startAfterCoreConnected(service, guid, config)
        }
        // The exit IP/country is resolved by the service after the core is really running.
        // Psiphon is probed only after its SOCKS listener is ready, so the result is never the
        // upstream Xray server's address.
        val secondLayerReady = if (config.torEnabled) TorBridge.activeSocksPort() in 1..65535
            else PsiphonBridge.activeSocksPort() in 1..65535
        if ((!config.psiphonEnabled && !config.torEnabled) || secondLayerReady) {
            scheduleAutomaticExitProbe(guid)
        }
        if (isReload && (config.psiphonEnabled || config.torEnabled) && secondLayerReady) {
            // Refresh the visible exit address after Xray switches to the second layer.
            scheduleAutomaticExitProbe(guid)
        }
        if (!waitingForSecondLayerVpn) NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /** Starts the separately packaged official AmneziaWG Go engine on Android's TUN. */
    private fun startAmneziaWg(
        service: Service,
        guid: String,
        config: ProfileItem,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean,
    ) {
        val vpnService = service as? CoreVpnService
            ?: error("AmneziaWG requires a VPN service to run")
        val chained = config.psiphonEnabled || config.torEnabled
        if (chained) check(com.v2ray.ang.service.TProxyService.isNativeAvailable()) {
            "The selected second layer over AmneziaWG requires the HEV native tunnel"
        }
        val tun = vpnInterface
        if (!chained) check(tun != null && tun.fileDescriptor.valid()) { "Android VPN TUN is not available" }
        val sourceConfig = config.amneziawgConfig
            ?: MmkvManager.decodeServerRaw(guid)
            ?: error("AmneziaWG configuration was not found in the profile")
        val uapi = AmneziaWgFmt.toGoUapi(sourceConfig) { host ->
            resolveAmneziaWgEndpoint(service, host)
        }
        val uapiLines = uapi.lineSequence().toList()
        val peerCount = uapiLines.count { it.startsWith("public_key=") }
        val customPacketCount = uapiLines.count { it.startsWith("i1=") || it.startsWith("i2=") ||
            it.startsWith("i3=") || it.startsWith("i4=") || it.startsWith("i5=") }
        fun uapiValue(key: String): String = uapiLines.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.takeIf(String::isNotBlank) ?: "unset"
        fun uapiFlag(key: String): String = when (uapiValue(key)) {
            "1", "true" -> "on"
            "0", "false" -> "off"
            else -> "unset"
        }
        // Do not call the native awgVersion() entry point here. On some
        // previously extracted libwg-go.so builds that diagnostic function
        // itself panics before awgTurnOn, making a valid tunnel look broken.
        // The version is pinned and embedded by the Gradle native marker.
        val engineVersion = BuildConfig.AMNEZIAWG_ENGINE_VERSION
        check(!coreControllerDelegate.isInitialized()) {
            "AmneziaWG cannot start in a process that already loaded Xray"
        }
        check(service is AmneziaWgVpnService) {
            "AmneziaWG requires its isolated VPN service"
        }
        PingNgDiagnostics.record("AmneziaWG FIX14 process isolation verified: pid=${android.os.Process.myPid()}, service=${service.javaClass.simpleName}, xrayLoaded=false")
        // This is a plain C JNI call: verifies the actual loaded binary, without
        // entering Go's runtime or trusting only the Gradle version constant.
        val nativeBuild = GoBackend.awgBuildId()
        check(nativeBuild == "PingNG-AWG-FIX15-PSIPHON-NETSTACK-20261005") {
            "Unexpected AmneziaWG native build: $nativeBuild"
        }
        PingNgDiagnostics.record("AmneziaWG native build verified: $nativeBuild")
        val uapiShape = uapiLines.filter(String::isNotBlank).joinToString(",") { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            when (key) {
                "private_key", "header_protection_key", "public_key", "preshared_key", "endpoint" ->
                    "$key(len=${value.length})"
                else -> "$key=$value"
            }
        }
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: AmneziaWG UAPI shape: $uapiShape")
        PingNgDiagnostics.record(
            "AmneziaWG native start prepared: app=${BuildConfig.VERSION_NAME}, engine=$engineVersion, " +
                "peers=$peerCount, jc=${uapiValue("jc")}, jmin=${uapiValue("jmin")}, " +
                "jmax=${uapiValue("jmax")}, s1=${uapiValue("s1")}, s2=${uapiValue("s2")}, " +
                "s3=${uapiValue("s3")}, s4=${uapiValue("s4")}, " +
                "h1=${uapiValue("h1")}, h2=${uapiValue("h2")}, h3=${uapiValue("h3")}, " +
                "h4=${uapiValue("h4")}, headerProtection=${if (uapiValue("header_protection_key") == "unset") "off" else "on"}, " +
                "randomTrailers=${uapiFlag("random_trailers")}, disableCookies=${uapiFlag("disable_cookies")}, " +
                "customPacketFields=$customPacketCount, tunOwnership=${if (chained) "netstack" else "direct"}"
        )
        // Match the official Android backend: transfer the established TUN descriptor itself to
        // the Go engine. Keeping a duplicate as the manager's lifecycle handle avoids sending a
        // duplicated descriptor into CreateUnmonitoredTUNFromFD while still supporting reloads.
        val lifecycleTun = if (chained) null else ParcelFileDescriptor.dup(tun!!.fileDescriptor)
        val handle = try {
            if (chained) {
                val awg = AmneziaWgFmt.readConfig(sourceConfig)
                val dns = AmneziaWgFmt.effectiveDnsServers(
                    profileDns = awg.dnsServers,
                    fallbackDns = SettingsManager.getVpnDnsServers(),
                )
                require(dns.isNotEmpty()) { "AmneziaWG needs an IP DNS server for the selected second layer" }
                PingNgDiagnostics.record(
                    "AmneziaWG netstack DNS prepared: profile=${awg.dnsServers.size}, usable=${dns.size}",
                )
                GoBackend.awgStartProxy(uapi, awg.addresses.joinToString(","), dns.joinToString(","),
                    awg.mtu ?: 1280,
                    if (config.torEnabled) TorBridge.prepareAmneziaEgress() else PsiphonBridge.prepareAmneziaEgress())
            } else {
                GoBackend.awgTurnOn("awg0", tun!!.detachFd(), uapi)
            }
        } catch (error: Throwable) {
            runCatching { lifecycleTun?.close() }
            throw error
        }
        if (handle < 0) {
            runCatching { lifecycleTun?.close() }
            error("AmneziaWG could not start the tunnel")
        }
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: AmneziaWG native engine loaded: version=$engineVersion")
        PingNgDiagnostics.record("AmneziaWG native engine loaded: version=$engineVersion")
        amneziaWgHandle = handle
        amneziaWgLifecycleTun = lifecycleTun
        try {
            listOf(GoBackend.awgGetSocketV4(handle), GoBackend.awgGetSocketV6(handle))
                .filter { it >= 0 }
                .distinct()
                .forEach { socketFd ->
                    check(vpnService.protect(socketFd)) {
                        "Android could not protect the AmneziaWG socket from the VPN"
                    }
                }
        } catch (error: Throwable) {
            stopAmneziaWgEngine()
            throw error
        }

        if (chained && config.psiphonEnabled) {
            // An IP retained from a previous unchained WARP session must never
            // be presented as Psiphon's exit while its SOCKS gateway starts.
            config.lastExitIpAddress = null
            config.lastExitCountryCode = null
            MmkvManager.encodeServerConfig(guid, config)
        }
        currentConfig = config
        currentConfigGuid = guid
        currentVpnInterface = if (chained) vpnInterface else lifecycleTun
        MmkvManager.setCoreServiceStatus(true)
        NotificationManager.showNotification(config)
        PingNgDiagnostics.record("AmneziaWG native engine is running")
        resetProbeScope()
        if (chained) {
            val upstreamHttpPort = GoBackend.awgProxyPort(handle, 0)
            if (config.torEnabled) {
                TorBridge.setAmneziaUpstream(upstreamHttpPort)
                PingNgDiagnostics.record("Tor Over AmneziaWG: bootstrap HTTP -> AWG netstack; final TCP -> Tor")
                TorBridge.startAfterCoreConnected(service, guid, config)
            } else {
                PsiphonBridge.setAmneziaUpstream(upstreamHttpPort)
                PingNgDiagnostics.record(
                    "Psiphon Over AmneziaWG: bootstrap -> AWG netstack; app TCP -> Psiphon; UDP/443 blocked; other UDP -> AWG"
                )
                PsiphonBridge.startAfterCoreConnected(service, guid, config)
            }
        } else {
            if (!isReload) MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            if (isPingNgIncludedInAmneziaWgVpn() && isAmneziaWgFullTunnel()) scheduleAutomaticExitProbe(guid)
            NotificationManager.startSpeedNotification()
        }
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: AmneziaWG engine started successfully")
    }

    /** Resolves peer domains on a physical network before the VPN TUN captures DNS traffic. */
    private fun resolveAmneziaWgEndpoint(service: Service, host: String): List<InetAddress> {
        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return InetAddress.getAllByName(host).toList()
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val resolved = runCatching { network.getAllByName(host).toList() }.getOrNull()
            if (!resolved.isNullOrEmpty()) return resolved
        }
        return InetAddress.getAllByName(host).toList()
    }

    private fun isPingNgIncludedInAmneziaWgVpn(): Boolean {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) != true) return true
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) return true
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Direct AmneziaWG protects its own sockets. In proxy/allowlist mode the
        // VPN builder adds PingNG automatically for in-app exit checks, while
        // bypass mode still honors an explicit opt-out for PingNG.
        return if (bypassApps) BuildConfig.APPLICATION_ID !in apps else true
    }

    private fun isAmneziaWgFullTunnel(): Boolean {
        val raw = currentConfig?.amneziawgConfig
            ?: currentConfigGuid?.let(MmkvManager::decodeServerRaw)
            ?: return false
        return runCatching {
            AmneziaWgFmt.readConfig(raw).routes.any { it == "0.0.0.0/0" || it == "::/0" }
        }.getOrDefault(false)
    }

    fun amneziaFinalSocksPort(): Int = if (amneziaWgHandle >= 0 &&
        currentConfig?.configType == EConfigType.AMNEZIAWG &&
        (currentConfig?.psiphonEnabled == true || currentConfig?.torEnabled == true)
    ) {
        // This is the legacy native gateway. Psiphon TCP must use its own
        // listener; HEV uses this port only for DNS UDP in Psiphon mode.
        GoBackend.awgProxyPort(amneziaWgHandle, 1)
    } else 0

    private fun stopAmneziaWgEngine() {
        val handle = amneziaWgHandle
        if (handle >= 0) {
            amneziaWgHandle = -1
            runCatching { GoBackend.awgTurnOff(handle) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to stop AmneziaWG engine", it) }
        }
        amneziaWgLifecycleTun?.let { lifecycleTun ->
            runCatching { lifecycleTun.close() }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to close AmneziaWG lifecycle TUN", it) }
            amneziaWgLifecycleTun = null
        }
        MmkvManager.setCoreServiceStatus(false)
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(caller: Service? = null): Boolean {
        val service = getService() ?: run {
            caller?.let { BepassBridge.stop(it, force = true) }
            MasterDnsBridge.cancelStartup()
            MasterDnsBridge.stop()
            SoarBridge.cancelStartup()
            SoarBridge.stop()
            TorBridge.stop()
            return false
        }
        // Android may deliver onDestroy for the temporary proxy service after
        // the real VPN service has already been created. An old service must
        // never tear down the new service through this process-global manager.
        if (caller != null && service !== caller) {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: ignoring stale stop from ${caller.javaClass.simpleName}")
            return false
        }

        stopping = true
        lifecycleGeneration += 1
        BepassBridge.stop(service, force = true)
        PsiphonBridge.stop()
        TorBridge.stop()
        WarpMasqueBridge.stop()
        MasterDnsBridge.stop()
        SoarBridge.stop()
        WarpMasqueDesyncProxy.stop()
        autoExitProbeJob?.cancel()
        autoExitProbeJob = null
        probeScope?.cancel()
        probeScope = null

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null
        currentConfig = null
        currentConfigGuid = null

        if (amneziaWgHandle >= 0) {
            stopAmneziaWgEngine()
            MmkvManager.setCoreServiceStatus(false)
        } else if (coreStopJob?.isActive != true && isRunning()) {
            coreStopJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                    PingNgDiagnostics.record("Core stop completed")
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                } finally {
                    MmkvManager.setCoreServiceStatus(false)
                }
            }
        } else if (coreStopJob?.isActive != true) {
            MmkvManager.setCoreServiceStatus(false)
        }
        PingNgDesyncManager.stop()
        PingNgDiagnostics.record("Core stop requested")

        // Close existing browser dialer
        if (coreControllerDelegate.isInitialized()) {
            CoreNativeManager.reconcileBrowserDialer("")
        }
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        if (receiverRegistered) {
            try {
                service.unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
            } finally {
                receiverRegistered = false
            }
        }

        return true
    }

    /** Rebuilds the Xray route after Psiphon has obtained its local SOCKS port. */
    fun onPsiphonConnected() {
        if (stopping) return
        val service = getService()
        CoroutineScope(Dispatchers.Default).launch {
            if (service is CoreVpnService && currentVpnInterface == null) {
                try {
                    service.startVpnAfterPsiphon()
                } catch (error: Throwable) {
                    val reason = error.message ?: error.javaClass.simpleName
                    PingNgDiagnostics.record("Psiphon VPN attachment failed: $reason", error)
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
                    service.stopService()
                }
            } else if (PsiphonBridge.usesStableEgress(currentConfig)) {
                PingNgDiagnostics.record("Psiphon connected; preserving upstream and prepared SOCKS route")
                currentConfigGuid?.let(::scheduleAutomaticExitProbe)
            } else {
                reloadCore()
            }
        }
    }

    /** A Psiphon tunnel is changing servers; discard probes against its stale SOCKS session. */
    fun onPsiphonConnecting() {
        autoExitProbeJob?.cancel()
        autoExitProbeJob = null
        currentConfig?.let(NotificationManager::showNotification)
    }

    /** Refreshes foreground state only after a Psiphon exit probe has verified the route. */
    fun onPsiphonEgressVerified() {
        if (stopping || !PsiphonBridge.isEgressVerified()) return
        currentConfig?.let(NotificationManager::showNotification)
        NotificationManager.startSpeedNotification()
    }

    /** Recheck the user-visible connection state after Psiphon restores the same SOCKS listener. */
    fun onPsiphonRecovered() {
        if (stopping || !PsiphonBridge.isConnected()) return
        currentConfigGuid?.let(::scheduleAutomaticExitProbe)
    }

    fun onPsiphonDisconnected() {
        if (stopping) return
        currentConfig?.let(NotificationManager::showNotification)
        val service = getService() ?: return
        if (service is CoreVpnService && currentVpnInterface == null) {
            service.stopService()
            return
        }
        if (!isRunning()) return
        if (PsiphonBridge.usesStableEgress(currentConfig)) {
            PingNgDiagnostics.record("Psiphon reconnecting; keeping the reserved SOCKS route fail-closed")
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            reloadCore()
        }
    }

    /** Stops a VPN bootstrap cleanly when Psiphon fails before TUN exists. */
    fun onPsiphonFailed(message: String) {
        val service = getService() ?: return
        if (service is CoreVpnService && isRunning() &&
            (currentVpnInterface == null || currentConfig?.configType == EConfigType.AMNEZIAWG)) {
            val reason = message.ifBlank { "Psiphon connection failed" }
            PingNgDiagnostics.record("VPN startup canceled: $reason")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
            service.stopService()
        }
    }

    /** Silently tears down an unverified Psiphon route so the user can retry. */
    fun onPsiphonEgressFailed() {
        if (stopping || currentConfig?.psiphonEnabled != true) return
        autoExitProbeJob?.cancel()
        autoExitProbeJob = null
        PingNgDiagnostics.record("Psiphon SOCKS egress verification failed; disconnecting the active VPN")
        val control = serviceControl?.get()
        if (control == null) {
            PsiphonBridge.stop()
            return
        }
        // ServiceControl performs the owned VPN/core teardown and emits a normal stopped state.
        // Do not send START_FAILURE: the user requested a quiet disconnect instead of a SOCKS error.
        control.stopService()
    }

    /** Converts the Xray-only Psiphon bootstrap into the actual Android VPN. */
    fun promoteBootstrappedCoreToVpn(vpnInterface: ParcelFileDescriptor): Boolean {
        val service = getService() ?: return false
        currentVpnInterface = vpnInterface
        currentConfig?.let(NotificationManager::showNotification)
        if (currentConfig?.configType == EConfigType.AMNEZIAWG && currentConfig?.torEnabled == true) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            currentConfigGuid?.let(::scheduleAutomaticExitProbe)
            NotificationManager.startSpeedNotification()
            PingNgDiagnostics.record("Tor Over AmneziaWG VPN attached; AWG bootstrap preserved")
            return true
        }
        if (currentConfig?.configType == EConfigType.AMNEZIAWG && currentConfig?.psiphonEnabled == true) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            currentConfigGuid?.let(::scheduleAutomaticExitProbe)
            NotificationManager.startSpeedNotification()
            PingNgDiagnostics.record("Psiphon Over AmneziaWG VPN attached; AWG bootstrap preserved")
            return true
        }
        if (PsiphonBridge.usesStableEgress(currentConfig)) {
            // HEV sends VPN traffic to Xray's already-running SOCKS inbound.
            // Restarting Xray here closes Psiphon's HTTP upstream and forces it
            // to reconnect immediately after reporting CONNECTED.
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            currentConfigGuid?.let(::scheduleAutomaticExitProbe)
            PingNgDiagnostics.record("Psiphon VPN attached without restarting MasterDNS upstream")
            return true
        }
        val promoted = reloadCore()
        if (promoted) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        return promoted
    }

    /** Attaches the Android VPN after Tor has established its first circuit. */
    fun onTorConnected() {
        if (stopping || !TorBridge.isConnected()) return
        val service = getService() ?: return
        currentConfig?.let(NotificationManager::showNotification)
        NotificationManager.startSpeedNotification()
        CoroutineScope(Dispatchers.Default).launch {
            if (service is CoreVpnService && currentVpnInterface == null) {
                try {
                    service.startVpnAfterTor()
                } catch (error: Throwable) {
                    val reason = error.message ?: error.javaClass.simpleName
                    PingNgDiagnostics.record("Tor VPN attachment failed: $reason", error)
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
                    service.stopService()
                }
            } else if (TorBridge.usesStableEgress(currentConfig)) {
                PingNgDiagnostics.record("Tor connected; preserving its upstream and prepared SOCKS route")
                currentConfigGuid?.let(::scheduleAutomaticExitProbe)
            } else {
                // The core was prepared with Tor's reserved local SOCKS listener
                // before Tor started. Once the circuit is ready, only the exit
                // check needs to run; rebuilding Xray would tear down Tor's
                // configured upstream proxy.
                currentConfigGuid?.let(::scheduleAutomaticExitProbe)
            }
        }
    }

    fun onTorDisconnected() {
        if (stopping) return
        autoExitProbeJob?.cancel()
        autoExitProbeJob = null
        currentConfig?.let(NotificationManager::showNotification)
        val service = getService() ?: return
        if (service is CoreVpnService && currentVpnInterface == null) {
            service.stopService()
            return
        }
        // Every Tor route points at its reserved loopback SOCKS port. If Tor
        // reconnects, connections fail closed during the gap and recover on
        // that same listener without changing the selected upstream.
        PingNgDiagnostics.record("Tor circuit changed; waiting for the local SOCKS listener to recover")
    }

    fun onTorFailed(message: String) {
        val service = getService() ?: return
        if (service is CoreVpnService && isRunning() &&
            (currentVpnInterface == null || currentConfig?.configType == EConfigType.AMNEZIAWG)
        ) {
            val reason = message.ifBlank { "Tor connection failed" }
            PingNgDiagnostics.record("VPN startup canceled: $reason")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
            service.stopService()
        }
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(): Boolean {
        if (stopping) return false
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false

        if (currentConfig?.configType == EConfigType.AMNEZIAWG) {
            if (currentConfig?.psiphonEnabled == true || currentConfig?.torEnabled == true) {
                // Keep both loopback listeners and HEV stable during a physical-network
                // handover. Rebind only AWG's protected UDP transport.
                return try {
                    listOf(GoBackend.awgGetSocketV4(amneziaWgHandle), GoBackend.awgGetSocketV6(amneziaWgHandle))
                        .filter { it >= 0 }.distinct().forEach {
                            check((service as CoreVpnService).protect(it)) { "Could not protect AWG socket" }
                        }
                    if (currentConfig?.torEnabled == true) {
                        autoExitProbeJob?.cancel()
                        autoExitProbeJob = null
                        PingNgDiagnostics.record("Tor Over AmneziaWG transport handover; local gateways preserved")
                    } else {
                        onPsiphonConnecting()
                        PingNgDiagnostics.record("Psiphon Over AmneziaWG transport handover; local gateways preserved")
                    }
                    true
                } catch (error: Exception) {
                    if (currentConfig?.torEnabled == true) onTorFailed(error.message.orEmpty())
                    else onPsiphonFailed(error.message.orEmpty())
                    false
                }
            }
            return try {
                val tun = amneziaWgLifecycleTun ?: currentVpnInterface
                check(tun != null && tun.fileDescriptor.valid()) { "Android VPN TUN is not available" }
                // The previous engine owns the original descriptor. Duplicate a fresh descriptor
                // before stopping it; the new start path will transfer that descriptor directly.
                val reloadTun = ParcelFileDescriptor.dup(tun.fileDescriptor)
                isReloading = true
                lifecycleGeneration += 1
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: AmneziaWG network reload start")
                stopAmneziaWgEngine()
                launchCore(service, reloadTun, isReload = true)
                if (currentConfig?.let(AwgWarpConfig::isProfile) == true) {
                    currentConfigGuid?.let(::scheduleAutomaticExitProbe)
                }
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: AmneziaWG network reload finished")
                true
            } catch (e: Exception) {
                val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload AmneziaWG: $message", e)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                false
            } finally {
                isReloading = false
            }
        }

        return try {
            val tunFd = currentVpnInterface

            isReloading = true
            lifecycleGeneration += 1
            autoExitProbeJob?.cancel()
            autoExitProbeJob = null
            probeScope?.cancel()
            probeScope = null
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            coreController.stopLoop()
            launchCore(service, tunFd, isReload = true)

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning() || amneziaWgHandle >= 0) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay(): Job? {
        if (stopping) {
            return null
        }

        val selectedProfile = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
        if (selectedProfile?.configType == EConfigType.OPENVPN) {
            val vpnService = getService() as? CoreVpnService ?: return null
            return if (isOpenVpnActive()) vpnService.testOpenVpnConnection() else null
        }
        if (!isRunning()) return null

        val generation = lifecycleGeneration
        val psiphonEpoch = PsiphonBridge.egressEpoch()
        val scope = probeScope ?: return null
        return scope.launch {
            val service = getService() ?: return@launch
            if (!isProbeCurrent(generation)) return@launch
            val dnsTunnelSocksPort = dnsTunnelConnectionProbePort(currentConfig)
            if (!isProfilePingable(currentConfig) && dnsTunnelSocksPort == null) {
                PingNgDiagnostics.record("Skipped delay check for unsupported profile type")
                return@launch
            }
            val psiphonProfile = currentConfig?.psiphonEnabled == true
            val torProfile = currentConfig?.torEnabled == true
            val secondLayerProfile = psiphonProfile || torProfile
            if (currentConfig?.configType == EConfigType.AMNEZIAWG &&
                !secondLayerProfile &&
                (!isPingNgIncludedInAmneziaWgVpn() || !isAmneziaWgFullTunnel())
            ) {
                val message = if (!isPingNgIncludedInAmneziaWgVpn()) {
                    "PingNG is not included in the VPN app list"
                } else {
                    "Exit testing is unavailable for AmneziaWG profiles with restricted routes"
                }
                val result = ConnectionTestResult(
                    delayMillis = -1L,
                    errorMessage = message,
                )
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)
                return@launch
            }
            var time = -1L
            var errorStr = ""
            val secondLayerSocksPort = PsiphonProbeRoutePolicy.socksPort(
                configType = currentConfig?.configType,
                psiphonEnabled = psiphonProfile,
                torEnabled = torProfile,
                amneziaGatewayPort = amneziaFinalSocksPort(),
                psiphonPort = PsiphonBridge.activeSocksPort(),
                torPort = TorBridge.activeSocksPort(),
            )
            if (PsiphonProbeRoutePolicy.waitForAmneziaGateway(
                    currentConfig?.configType, psiphonProfile, torProfile, amneziaFinalSocksPort(),
                )
            ) {
                PingNgDiagnostics.record("AmneziaWG second-layer probe postponed until its SOCKS gateway is ready")
                return@launch
            }
            val secondLayerConnected = when {
                torProfile -> TorBridge.isConnected()
                psiphonProfile -> PsiphonBridge.isConnected()
                else -> true
            }
            if (secondLayerProfile && (secondLayerSocksPort == null || !secondLayerConnected)) {
                PingNgDiagnostics.record("Connection test postponed while the second layer is reconnecting")
                return@launch
            }
            if (psiphonProfile && secondLayerSocksPort != null &&
                !SpeedtestManager.isLocalSocksListenerAvailable(secondLayerSocksPort)
            ) {
                PsiphonBridge.onEgressProbeFailed()
                return@launch
            }

            if (dnsTunnelSocksPort != null) {
                val isSoar = currentConfig?.let(SoarBridge::isProfile) == true
                val tunnelReady = if (isSoar) SoarBridge.isActive() else MasterDnsBridge.isActive()
                if (!tunnelReady) {
                    val result = ConnectionTestResult(
                        delayMillis = -1L,
                        errorMessage = service.getString(
                            if (isSoar) R.string.soar_connection_not_ready else R.string.dns_tunnel_connection_not_ready,
                        ),
                    )
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)
                    return@launch
                }

                val liveSocksPort = secondLayerSocksPort ?: dnsTunnelSocksPort
                val time = if (secondLayerSocksPort != null) {
                    measureLiveProxyDelay(5_000, secondLayerSocksPort)
                } else {
                    measureDnsTunnelDelay(5_000, dnsTunnelSocksPort)
                }
                if (!isProbeCurrent(generation)) return@launch
                val errorMessage = if (psiphonProfile && time < 0L) {
                    service.getString(R.string.connection_test_second_layer_timeout)
                } else {
                    ""
                }
                val result = ConnectionTestResult(delayMillis = time, errorMessage = errorMessage)
                if (!psiphonProfile) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)
                }
                val exitIpVerified = fetchAndPublishExitIp(
                    service = service,
                    result = result,
                    forceLiveProxyProbe = secondLayerSocksPort != null,
                    preferLiveSocksForExitIp = shouldPreferLiveSocksForCurrentProfile(),
                    publishResult = !psiphonProfile,
                    socksPortOverride = liveSocksPort.takeIf { secondLayerSocksPort == null },
                )
                if (psiphonProfile) {
                    if (!isProbeCurrent(generation) || !PsiphonProbeRoutePolicy.isCurrentSession(
                            psiphonEpoch, PsiphonBridge.egressEpoch(), PsiphonBridge.isConnected())) return@launch
                    if (time < 0L && !exitIpVerified) {
                        PsiphonBridge.onEgressProbeFailed()
                        return@launch
                    }
                    if (service is CoreVpnService && currentVpnInterface == null) {
                        PingNgDiagnostics.record("Psiphon egress verified; waiting for the VPN interface")
                        return@launch
                    }
                    MessageHelper.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_RESULT,
                        if (exitIpVerified) withCachedExitIp(result) else result.copy(profileGuid = currentConfigGuid),
                    )
                    markPsiphonEgressVerified(service)
                }
                return@launch
            }

            // Use the ready second-layer SOCKS listener for probes so the result
            // reports the actual egress and never falls back to the upstream proxy.
            val warpPlusProfile = currentConfig?.let { WarpPlusConfig.isDescription(it.description) } == true
            val forceLiveProxyProbe = service is CoreProxyOnlyService || secondLayerSocksPort != null ||
                currentConfig?.configType == EConfigType.AMNEZIAWG || warpPlusProfile
            val isAmneziaWgProfile = currentConfig?.configType == EConfigType.AMNEZIAWG

            // Desync search uses the explicit proxy-only service. Probe its
            // SOCKS inbound first so every candidate is measured through real
            // Xray traffic rather than a direct outbound measurement.
            val liveProbeTimeoutMs = if (service is CoreProxyOnlyService) 1_500 else 5_000
            if (forceLiveProxyProbe) {
                time = if (isAmneziaWgProfile && !secondLayerProfile) {
                    measureDirectAmneziaWgDelay(liveProbeTimeoutMs)
                } else if (warpPlusProfile && secondLayerSocksPort == null) {
                    measureWarpPlusPageDelay(liveProbeTimeoutMs)
                } else {
                    // Probe both endpoints concurrently so a blocked primary URL
                    // does not add a second sequential timeout per candidate.
                    measureLiveProxyDelay(liveProbeTimeoutMs, secondLayerSocksPort)
                }
                if (time >= 0L) {
                    PingNgDiagnostics.record(
                        if (isAmneziaWgProfile) {
                            "Connection delay measured through the AmneziaWG VPN route"
                        } else if (secondLayerSocksPort != null) {
                            "Connection delay measured through the actual second-layer SOCKS route"
                        } else if (warpPlusProfile) {
                            "WARP Plus page probe measured through the active Xray SOCKS route"
                        } else {
                            "Desync candidate measured through live Xray SOCKS"
                        }
                    )
                }
            }

            if (!forceLiveProxyProbe && time < 0L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                } catch (e: Exception) {
                    if (!isProbeCurrent(generation)) return@launch
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }
            if (!isProbeCurrent(generation)) return@launch
            if (!forceLiveProxyProbe && time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    if (!isProbeCurrent(generation)) return@launch
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            if (!isProbeCurrent(generation)) return@launch
            // The standalone core probe may not use the live dialer chain. Retry through the
            // running SOCKS inbound so an active PingNG Desync route is measured correctly.
            if (!forceLiveProxyProbe && time < 0L) {
                time = SpeedtestManager.liveTunnelDelay(
                    SettingsManager.getDelayTestUrl(),
                    liveProbeTimeoutMs,
                )
                if (time < 0L) {
                    time = SpeedtestManager.liveTunnelDelay(
                        SettingsManager.getDelayTestUrl(true),
                        liveProbeTimeoutMs,
                    )
                }
                if (time >= 0L) {
                    errorStr = ""
                    PingNgDiagnostics.record("Delay measured through the live SOCKS tunnel")
                }
            }

            if (!isProbeCurrent(generation)) return@launch
            if (secondLayerProfile && when {
                    torProfile -> !TorBridge.isConnected()
                    else -> !PsiphonBridge.isConnected()
                }) {
                PingNgDiagnostics.record("Discarded connection-test result; the second layer began reconnecting")
                return@launch
            }
            if (psiphonProfile && time < 0L && errorStr.isBlank()) {
                errorStr = service.getString(R.string.connection_test_second_layer_timeout)
            }
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
            )
            if (!psiphonProfile) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)
            }

            // Fetch the exit IP even when the standalone delay endpoint returns -1. Some DPI
            // networks block the delay endpoint while ordinary tunnel traffic is working.
            val exitIpVerified = fetchAndPublishExitIp(
                service = service,
                result = result,
                forceLiveProxyProbe = forceLiveProxyProbe,
                preferLiveSocksForExitIp = shouldPreferLiveSocksForCurrentProfile(),
                publishResult = !psiphonProfile,
            )
            if (psiphonProfile) {
                if (!isProbeCurrent(generation) || !PsiphonProbeRoutePolicy.isCurrentSession(
                        psiphonEpoch, PsiphonBridge.egressEpoch(), PsiphonBridge.isConnected())) return@launch
                if (time < 0L && !exitIpVerified) {
                    PsiphonBridge.onEgressProbeFailed()
                    return@launch
                }
                if (service is CoreVpnService && currentVpnInterface == null) {
                        PingNgDiagnostics.record(
                            if (currentConfig?.configType == EConfigType.AMNEZIAWG) {
                                "AmneziaWG user route verified; waiting for the Android VPN interface"
                            } else {
                                "Psiphon SOCKS egress verified; waiting for the VPN interface"
                            },
                        )
                    return@launch
                }
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_MEASURE_DELAY_RESULT,
                    if (exitIpVerified) withCachedExitIp(result) else result.copy(profileGuid = currentConfigGuid),
                )
                markPsiphonEgressVerified(service)
            }
        }
    }

    private suspend fun measureLiveProxyDelay(
        timeoutMs: Int,
        socksPortOverride: Int? = null,
    ): Long = coroutineScope {
        if (socksPortOverride != null) {
            // A single IP-information API can be filtered even when ordinary sites
            // work. Probe the configured delay URLs first, then the exit-IP API.
            // Keep these sequential: parallel probes overload a SOCKS listener while
            // Psiphon is replacing its upstream tunnel.
            val probeUrls = PsiphonRoutingPolicy.liveEgressProbeUrls(
                SettingsManager.getDelayTestUrl(),
                SettingsManager.getDelayTestUrl(true),
                "https://cp.cloudflare.com/generate_204",
                "http://1.1.1.1/cdn-cgi/trace",
                SpeedtestManager.getRemoteIpInfoUrl(),
            )
            val probeTimeoutMs = timeoutMs.coerceAtMost(2_000)
            probeUrls.forEachIndexed { index, url ->
                val delay = SpeedtestManager.liveTunnelDelay(url, probeTimeoutMs, socksPortOverride)
                if (delay >= 0L) {
                    if (index > 0) PingNgDiagnostics.record("Second-layer SOCKS probe succeeded on fallback endpoint ${index + 1}")
                    return@coroutineScope delay
                }
            }
            PingNgDiagnostics.record("Second-layer SOCKS probes timed out on ${probeUrls.size} configured endpoints")
            return@coroutineScope -1L
        }
        val urls = listOf(
            SettingsManager.getDelayTestUrl(),
            SettingsManager.getDelayTestUrl(true),
            SpeedtestManager.getRemoteIpInfoUrl(),
        ).distinct()
        val probes = urls.map { url ->
            async(Dispatchers.IO) {
                SpeedtestManager.liveTunnelDelay(url, timeoutMs, socksPortOverride)
            }
        }
        try {
            val results = probes.awaitAll()
            results.firstOrNull { it >= 0L } ?: -1L
        } finally {
            probes.forEach { it.cancel() }
        }
    }

    /** Checks several page endpoints through WARP Plus's local core SOCKS route. */
    private suspend fun measureWarpPlusPageDelay(
        timeoutMs: Int,
        socksPort: Int = SettingsManager.getSocksPort(),
    ): Long = coroutineScope {
        val urls = listOf(
            SettingsManager.getDelayTestUrl(),
            SettingsManager.getDelayTestUrl(true),
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "http://1.1.1.1/cdn-cgi/trace",
        ).distinct()
        val probes = urls.map { url ->
            async(Dispatchers.IO) {
                SpeedtestManager.liveTunnelDelay(url, timeoutMs, socksPort)
            }
        }
        try {
            val results = probes.awaitAll()
            val successIndex = results.indexOfFirst { it >= 0L }
            if (successIndex >= 0) {
                PingNgDiagnostics.record("WARP Plus page probe succeeded on endpoint ${successIndex + 1}")
                results[successIndex]
            } else {
                PingNgDiagnostics.record("WARP Plus page probe failed on all ${urls.size} endpoints")
                -1L
            }
        } finally {
            probes.forEach { it.cancel() }
        }
    }

    /** Tests the selected MasterDNS/DNSTT SOCKS route against independent endpoints. */
    private suspend fun measureDnsTunnelDelay(timeoutMs: Int, socksPort: Int): Long = coroutineScope {
        val urls = listOf(
            SettingsManager.getDelayTestUrl(),
            SettingsManager.getDelayTestUrl(true),
            SpeedtestManager.getRemoteIpInfoUrl(),
            "https://cp.cloudflare.com/generate_204",
        ).distinct()
        val probes = urls.map { url ->
            async(Dispatchers.IO) {
                SpeedtestManager.liveTunnelDelay(url, timeoutMs, socksPort)
            }
        }
        try {
            probes.awaitAll().firstOrNull { it >= 0L } ?: -1L
        } finally {
            probes.forEach { it.cancel() }
        }
    }

    private suspend fun measureDirectAmneziaWgDelay(timeoutMs: Int): Long = coroutineScope {
        val urls = listOf(
            SettingsManager.getDelayTestUrl(),
            SettingsManager.getDelayTestUrl(true),
            SpeedtestManager.getRemoteIpInfoUrl(),
        ).distinct()
        val probes = urls.map { url ->
            async(Dispatchers.IO) { SpeedtestManager.directTunnelDelay(url, timeoutMs) }
        }
        try {
            probes.awaitAll().firstOrNull { it >= 0L } ?: -1L
        } finally {
            probes.forEach { it.cancel() }
        }
    }

    /** Runs automatically after startup and retries briefly while the outbound route settles. */
    private fun scheduleAutomaticExitProbe(guid: String) {
        autoExitProbeJob?.cancel()
        val generation = lifecycleGeneration
        val scope = probeScope ?: return
        autoExitProbeJob = scope.launch {
            val quickSearchProbe = getService() is CoreProxyOnlyService
            val warpPlusProbe = currentConfig?.let { WarpPlusConfig.isDescription(it.description) } == true
            val psiphonProfile = currentConfig?.psiphonEnabled == true
            val torProfile = currentConfig?.torEnabled == true
            val secondLayerProfile = psiphonProfile || torProfile
            delay(
                PsiphonRuntimeStartPolicy.automaticEgressProbeDelayMs(
                    quickSearchProbe = quickSearchProbe,
                    psiphonProfile = psiphonProfile,
                    secondLayerProfile = secondLayerProfile,
                ),
            )
            if (warpPlusProbe && !quickSearchProbe) {
                // WARP Plus can carry ordinary traffic while Google's generate_204 endpoint
                // times out. Do not turn that endpoint-specific failure into a fake reconnect
                // signal; verify readiness through the same exit-IP route used by the profile.
                repeat(3) { attempt ->
                    if (!isProbeCurrent(generation) || currentConfigGuid != guid) return@launch
                    PingNgDiagnostics.record(
                        "Automatic WARP Plus readiness probe for ${currentConfig?.remarks.orEmpty()}"
                    )
                    if (probeWarpPlusExitIp(generation)) return@launch
                    if (attempt < 2) delay(1_200L)
                }
                return@launch
            }
            val service = getService() ?: return@launch
            if (!isProbeCurrent(generation) || currentConfigGuid != guid) return@launch
            if (psiphonProfile && service is CoreVpnService && currentVpnInterface == null) {
                PingNgDiagnostics.record("Psiphon egress check postponed until the VPN interface is attached")
                return@launch
            }
            if (secondLayerProfile && (if (torProfile) !TorBridge.isConnected() else !PsiphonBridge.isConnected())) {
                PingNgDiagnostics.record("Automatic connection check postponed; the second layer is reconnecting")
                return@launch
            }
            if (psiphonProfile) {
                val psiphonSocksPort = PsiphonBridge.activeSocksPort()
                if (!SpeedtestManager.isLocalSocksListenerAvailable(psiphonSocksPort)) {
                    PsiphonBridge.onEgressProbeFailed()
                    return@launch
                }
                PingNgDiagnostics.record(
                    if (currentConfig?.configType == EConfigType.AMNEZIAWG) {
                        "Automatic AmneziaWG exit check after Psiphon bootstrap readiness"
                    } else {
                        "Automatic Psiphon connection check: measuring the live SOCKS route"
                    }
                )
                measureV2rayDelay()?.join()
                return@launch
            }
            // Publish the exit IP first. A delay endpoint can be blocked or
            // slow even when the actual tunnel is usable; waiting for that
            // request made the UI keep showing only "Connected". This is the
            // service-side equivalent of tapping "Tap to Check Connection".
            PingNgDiagnostics.record("Automatic connection check: resolving exit IP")
            fetchAndPublishExitIp(
                service = service,
                result = ConnectionTestResult(delayMillis = -1L),
                forceLiveProxyProbe = quickSearchProbe,
                preferLiveSocksForExitIp = shouldPreferLiveSocksForCurrentProfile(),
            )
            val secondLayerActive = (if (torProfile) TorBridge.activeSocksPort() else PsiphonBridge.activeSocksPort()) in 1..65535
            repeat(if (quickSearchProbe || secondLayerActive) 1 else 3) { attempt ->
                if (!isProbeCurrent(generation) || currentConfigGuid != guid) return@launch
                PingNgDiagnostics.record("Automatic exit IP probe for ${currentConfig?.remarks.orEmpty()}")
                measureV2rayDelay()?.join()
                if (!quickSearchProbe && !secondLayerActive && attempt < 2) delay(1200L)
            }
            if (currentConfig?.let(AwgWarpConfig::isProfile) == true &&
                currentConfig?.autoScanEndpoint != false && !secondLayerProfile
            ) monitorAwgEndpoint(guid, generation)
        }
    }

    /** A started Go engine is not proof of a completed handshake or working route. */
    private suspend fun monitorAwgEndpoint(guid: String, generation: Long) {
        var consecutiveFailures = 0
        while (isProbeCurrent(generation) && currentConfigGuid == guid) {
            delay(10_000L)
            if (!isProbeCurrent(generation) || currentConfigGuid != guid) return
            val reachable = SpeedtestManager.directTunnelDelay(
                "http://1.1.1.1/cdn-cgi/trace", 3_000,
            ) >= 0L || SpeedtestManager.directTunnelDelay(
                "http://1.0.0.1/cdn-cgi/trace", 3_000,
            ) >= 0L
            if (reachable) {
                consecutiveFailures = 0
                continue
            }
            consecutiveFailures++
            if (consecutiveFailures < 2 || awgFailoverCount >= 2) continue
            val profile = currentConfig ?: return
            val candidates = EndpointScannerCli.decodeCandidates(profile.awgEndpointCandidates)
                .filter { it.latencyMs >= 0L }
            val currentEndpoint = AmneziaWgFmt.value(profile.amneziawgConfig.orEmpty(), "Peer", "Endpoint")
            val currentIndex = candidates.indexOfFirst { it.endpoint == currentEndpoint }
            val next = candidates.drop(currentIndex + 1).firstOrNull { it.endpoint != currentEndpoint }
                ?: return
            if (!isProbeCurrent(generation) || currentConfigGuid != guid) return
            awgFailoverCount++
            PingNgDiagnostics.record("WARP AWG route failed twice; trying next scanned endpoint: ${next.endpoint}")
            val (host, port) = AmneziaWgFmt.splitEndpoint(next.endpoint)
            val updated = profile.copy(
                amneziawgConfig = AmneziaWgFmt.setField(
                    profile.amneziawgConfig.orEmpty(), "Peer", "Endpoint", next.endpoint,
                ),
                server = host,
                serverPort = port,
            )
            MmkvManager.encodeServerConfig(guid, updated)
            if (!reloadCore()) {
                MmkvManager.encodeServerConfig(guid, profile)
            }
            return
        }
    }

    /** Gives each core instance its own cancellable probe scope. */
    private fun resetProbeScope() {
        probeScope?.cancel()
        probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun isProbeCurrent(generation: Long): Boolean {
        return !stopping && lifecycleGeneration == generation && isRunning()
    }

    private fun markPsiphonEgressVerified(service: Service) {
        if (service is CoreVpnService && currentVpnInterface == null) {
            val message = if (PsiphonProbeRoutePolicy.usesPsiphonFinalEgress(
                    currentConfig?.configType,
                    currentConfig?.psiphonEnabled == true,
                    currentConfig?.torEnabled == true,
                )
            ) {
                "Psiphon TCP exit IP checked through AWG gateway; waiting for the Android VPN interface"
            } else {
                "Psiphon SOCKS egress verified; waiting for the Android VPN interface"
            }
            PingNgDiagnostics.record(message)
            return
        }
        if (PsiphonProbeRoutePolicy.usesPsiphonFinalEgress(
                currentConfig?.configType,
                currentConfig?.psiphonEnabled == true,
                currentConfig?.torEnabled == true,
            )
        ) {
            PsiphonBridge.onEgressProbeSucceeded()
            PingNgDiagnostics.record("Psiphon TCP user egress verified through its direct SOCKS listener")
        } else if (currentConfig?.configType == EConfigType.AMNEZIAWG) {
            PsiphonBridge.onAmneziaBootstrapReady()
            PingNgDiagnostics.record("AmneziaWG user exit verified; Psiphon bootstrap runtime is connected")
        } else {
            PsiphonBridge.onEgressProbeSucceeded()
        }
    }

    private fun withCachedExitIp(result: ConnectionTestResult): ConnectionTestResult {
        val profile = currentConfig ?: return result
        return result.copy(
            countryCode = profile.lastExitCountryCode,
            ipAddress = profile.lastExitIpAddress,
            profileGuid = currentConfigGuid ?: result.profileGuid,
        )
    }

    /** Checks WARP Plus page readiness through its active Xray or second-layer SOCKS route. */
    private suspend fun probeWarpPlusExitIp(generation: Long): Boolean {
        if (!isProbeCurrent(generation)) return false
        val service = getService() ?: return false
        val psiphonProfile = currentConfig?.psiphonEnabled == true
        val torProfile = currentConfig?.torEnabled == true
        if (psiphonProfile && !PsiphonBridge.isConnected()) return false
        if (torProfile && !TorBridge.isConnected()) return false
        val secondLayerSocksPort = PsiphonProbeRoutePolicy.socksPort(
            configType = currentConfig?.configType,
            psiphonEnabled = psiphonProfile,
            torEnabled = torProfile,
            amneziaGatewayPort = amneziaFinalSocksPort(),
            psiphonPort = PsiphonBridge.activeSocksPort(),
            torPort = TorBridge.activeSocksPort(),
        )
        if ((psiphonProfile || torProfile) && secondLayerSocksPort == null) return false
        val delay = measureWarpPlusPageDelay(
            timeoutMs = 4_000,
            socksPort = secondLayerSocksPort ?: SettingsManager.getSocksPort(),
        )
        if (delay < 0L || !isProbeCurrent(generation)) return false
        return fetchAndPublishExitIp(
            service = service,
            result = ConnectionTestResult(delayMillis = delay),
            forceLiveProxyProbe = secondLayerSocksPort != null,
            preferLiveSocksForExitIp = secondLayerSocksPort == null,
            // The automatic check is the same user-visible operation as
            // tapping "Tap to Check Connection". Do not only cache the IP;
            // publish it so every connected profile displays its exit IP.
            publishResult = true,
        )
    }

    private fun fetchAndPublishExitIp(
        service: Service,
        result: ConnectionTestResult,
        forceLiveProxyProbe: Boolean,
        preferLiveSocksForExitIp: Boolean = false,
        publishResult: Boolean = true,
        socksPortOverride: Int? = null,
    ): Boolean {
        val probeGeneration = lifecycleGeneration
        val probeGuid = currentConfigGuid
        val psiphonEpoch = PsiphonBridge.egressEpoch()
        val psiphonProfile = currentConfig?.psiphonEnabled == true
        val torProfile = currentConfig?.torEnabled == true
        val secondLayerProfile = psiphonProfile || torProfile
        val secondLayerConnected = when {
            torProfile -> TorBridge.isConnected()
            psiphonProfile -> PsiphonBridge.isConnected()
            else -> true
        }
        if (secondLayerProfile && !secondLayerConnected) {
            PingNgDiagnostics.record("Exit IP check postponed while the second layer is reconnecting")
            return false
        }
        // An IP API response alone is not enough evidence that WARP Plus can
        // open pages. Publish its IP only after the live HTTP page probe has
        // returned, so a failed delay check cannot say "Tunnel active".
        if (currentConfig?.let { WarpPlusConfig.isDescription(it.description) } == true &&
            result.delayMillis < 0L
        ) return false
        val secondLayerSocksPort = PsiphonProbeRoutePolicy.socksPort(
            configType = currentConfig?.configType,
            psiphonEnabled = psiphonProfile,
            torEnabled = torProfile,
            amneziaGatewayPort = amneziaFinalSocksPort(),
            psiphonPort = PsiphonBridge.activeSocksPort(),
            torPort = TorBridge.activeSocksPort(),
        ) ?: 0
        if (PsiphonProbeRoutePolicy.waitForAmneziaGateway(
                currentConfig?.configType, psiphonProfile, torProfile, secondLayerSocksPort,
            )
        ) {
            PingNgDiagnostics.record("AmneziaWG exit IP postponed; final SOCKS gateway is unavailable")
            return false
        }
        val explicitSocksPort = socksPortOverride?.takeIf { it in 1..65535 }
        val isAmneziaWgProfile = currentConfig?.configType == EConfigType.AMNEZIAWG
        val preferCloudflareExitIp = PsiphonProbeRoutePolicy.shouldPreferCloudflareExitIp(
            configType = currentConfig?.configType,
            psiphonEnabled = psiphonProfile,
            torEnabled = torProfile,
        )
        val remoteIpInfo = when {
            explicitSocksPort != null -> {
                PingNgDiagnostics.record("Exit IP probe through DNS tunnel SOCKS 127.0.0.1:$explicitSocksPort")
                SpeedtestManager.getRemoteIPInfoThroughSocks(explicitSocksPort)
            }
            secondLayerSocksPort in 1..65535 -> {
                val routeName = if (PsiphonProbeRoutePolicy.usesPsiphonFinalEgress(
                        currentConfig?.configType,
                        psiphonProfile,
                        torProfile,
                    )
                ) {
                    "Psiphon direct TCP SOCKS over AWG bootstrap"
                } else if (isAmneziaWgProfile) {
                    "AmneziaWG second-layer SOCKS gateway"
                } else {
                    "second-layer SOCKS"
                }
                PingNgDiagnostics.record("Exit IP probe through $routeName 127.0.0.1:$secondLayerSocksPort")
                if (preferCloudflareExitIp) {
                    PingNgDiagnostics.record("Verifying Psiphon TCP egress with Cloudflare connection metadata")
                }
                SpeedtestManager.getRemoteIPInfoThroughSocks(
                    socksPortOverride = secondLayerSocksPort,
                    preferCloudflareEndpoint = preferCloudflareExitIp,
                )
            }
            isAmneziaWgProfile -> SpeedtestManager.getRemoteIPInfoDirect()
            forceLiveProxyProbe -> SpeedtestManager.getRemoteIPInfoThroughSocks()
            preferLiveSocksForExitIp -> {
                PingNgDiagnostics.record("ServerLess exit IP probe through the active core SOCKS route")
                SpeedtestManager.getRemoteIPInfoThroughSocks()
            }
            else -> SpeedtestManager.getRemoteIPInfo()
        } ?: run {
            // Keep the last verified IP visible if a transient probe fails.
            // The next successful probe replaces it; this prevents a slow
            // delay endpoint from erasing the IP that was just displayed by
            // the automatic connection check.
            if (publishResult && (!secondLayerProfile || (if (torProfile) TorBridge.isConnected() else PsiphonBridge.isConnected()))) {
                val profile = currentConfig
                val cachedIp = profile?.lastExitIpAddress?.takeIf { it.isNotBlank() }
                if (cachedIp != null && secondLayerSocksPort !in 1..65535 && explicitSocksPort == null &&
                    profile?.let { WarpPlusConfig.isDescription(it.description) } != true) {
                    MessageHelper.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_RESULT,
                        result.copy(
                            country = profile.lastExitCountryCode,
                            countryCode = profile.lastExitCountryCode,
                            ipAddress = cachedIp,
                            profileGuid = currentConfigGuid ?: result.profileGuid,
                        ),
                    )
                }
            }
            return false
        }

        if (!isProbeCurrent(probeGeneration) || currentConfigGuid != probeGuid ||
            (psiphonProfile && PsiphonBridge.egressEpoch() != psiphonEpoch) ||
            (secondLayerProfile && (if (torProfile) !TorBridge.isConnected() else !PsiphonBridge.isConnected()))
        ) {
            PingNgDiagnostics.record("Discarded exit-IP result; its tunnel session is no longer current")
            return false
        }

        val countryCode = remoteIpInfo.countryCode?.trim()?.uppercase()
            ?.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
        val ipAddress = remoteIpInfo.ipAddress?.trim()?.takeIf { it.isNotEmpty() }
        val profile = currentConfig ?: return false
        val changed = (countryCode != null && profile.lastExitCountryCode != countryCode) ||
            (ipAddress != null && profile.lastExitIpAddress != ipAddress)
        if (changed) {
            profile.lastExitCountryCode = countryCode ?: profile.lastExitCountryCode
            profile.lastExitIpAddress = ipAddress ?: profile.lastExitIpAddress
            currentConfigGuid?.let { activeGuid ->
                if (profile.warpRegistrationInternalProxy == true) {
                    MmkvManager.encodeEphemeralServerConfig(activeGuid, profile)
                } else {
                    MmkvManager.encodeServerConfig(activeGuid, profile)
                }
            }
        }
        if (publishResult) {
            MessageHelper.sendMsg2UI(
                service,
                AppConfig.MSG_MEASURE_DELAY_RESULT,
                result.copy(
                    country = remoteIpInfo.country,
                    countryCode = remoteIpInfo.countryCode,
                    ipAddress = remoteIpInfo.ipAddress,
                    profileGuid = currentConfigGuid ?: result.profileGuid,
                ),
            )
        }
        PingNgDiagnostics.record(
            "Automatic exit IP ready: ${ipAddress.orEmpty()} ${countryCode.orEmpty()}",
        )
        return true
    }

    private fun shouldPreferLiveSocksForCurrentProfile(): Boolean {
        val profile = currentConfig ?: return false
        val subscriptionUrl = MmkvManager.decodeSubscription(profile.subscriptionId)?.url
        return shouldPreferLiveSocksForServerLessExitIp(profile, subscriptionUrl)
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isAnyVpnRuntimeRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Disconnect requested")
                    runCatching {
                        serviceControl.stopService()
                        if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    }.onFailure {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: Disconnect request failed", it)
                    }
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
