package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.core.WarpMasqueBridge
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@SuppressLint("VpnServicePolicy")
open class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)
    private val startupCancelled = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startupJob: Job? = null

    fun isStartupCancelled(): Boolean = startupCancelled.get()

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        startupCancelled.set(true)
        WarpMasqueBridge.cancelStartup()
        MasterDnsBridge.cancelStartup()
        startupJob?.cancel()
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        startupCancelled.set(true)
        WarpMasqueBridge.cancelStartup()
        MasterDnsBridge.cancelStartup()
        startupJob?.cancel()
        // Startup can be stopped after Android has established the TUN but
        // before CoreServiceManager marks the service as running. Always run
        // teardown for that partially-started state too, or Android keeps the
        // VPN key active after the service is gone.
        if (isRunning || (::mInterface.isInitialized && mInterface.fileDescriptor.valid()) || CoreServiceManager.isRunning()) {
            stopAllService()
        }
        serviceScope.cancel()
        super.onDestroy()
        unlockStart()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            NotificationManager.ensureForeground()
            // Always-on VPN can restart the previously chosen component after
            // the selected profile changes. Route it before any native startup.
            val requiresAmneziaProcess = MmkvManager.getSelectServer()
                ?.let(MmkvManager::decodeServerConfig)?.configType == EConfigType.AMNEZIAWG
            if (requiresAmneziaProcess != (this is AmneziaWgVpnService)) {
                val target = if (requiresAmneziaProcess) {
                    AmneziaWgVpnService::class.java
                } else {
                    CoreVpnService::class.java
                }
                LogUtil.i(AppConfig.TAG, "FIX14: redirecting VPN startup to ${target.simpleName}")
                ContextCompat.startForegroundService(this, Intent(this, target))
                stopSelf()
                return START_NOT_STICKY
            }
            // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
            // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
            val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
            if (isSystemVpnStart) {
                unlockStart()
            }
            if (!tryLockStart()) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
                return START_NOT_STICKY
            }
            startupCancelled.set(false)
            val selectedConfigType = MmkvManager.getSelectServer()
                ?.let(MmkvManager::decodeServerConfig)
                ?.configType
            val hevEnabledForThisProfile = SettingsManager.isUsingHevTun() &&
                selectedConfigType != EConfigType.AMNEZIAWG
            LogUtil.i(
                AppConfig.TAG,
                "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart, " +
                    "hev=$hevEnabledForThisProfile"
            )
            // MASQUE endpoint scans and registration can block for tens of
            // seconds. Keep Android's service main thread free so Stop can
            // cancel the startup and close a TUN that was already created.
            val job = serviceScope.launch {
                try {
                    if (shouldBootstrapPsiphon()) {
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Holding VPN until Psiphon connects")
                        PingNgDiagnostics.record("VPN held until Psiphon connects")
                        if (!CoreServiceManager.isRunning() && !CoreServiceManager.startCoreLoop(null)) {
                            if (!startupCancelled.get()) {
                                unlockStart()
                                stopSelf()
                            }
                            return@launch
                        }
                        return@launch
                    }
                    prepareWarpMasqueRegistration()
                    check(!startupCancelled.get()) { "VPN startup canceled" }
                    if (!setupVpnService()) {
                        unlockStart()
                        stopSelf()
                        return@launch
                    }
                    check(!startupCancelled.get()) { "VPN startup canceled" }
                    startService()
                } catch (error: Throwable) {
                    if (startupCancelled.get() || error is CancellationException) {
                        PingNgDiagnostics.record("VPN startup canceled")
                        runCatching { stopAllService() }
                    } else {
                        val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
                        LogUtil.e(AppConfig.TAG, "StartCore-VPN: Unhandled startup failure: $message", error)
                        MessageHelper.sendMsg2UI(this@CoreVpnService, AppConfig.MSG_STATE_START_FAILURE, message)
                        runCatching { stopAllService() }
                    }
                }
            }
            startupJob = job
            job.invokeOnCompletion { if (startupJob === job) startupJob = null }
            return START_STICKY
        } catch (error: Throwable) {
            val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Unhandled startup failure: $message", error)
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, message)
            unlockStart()
            runCatching { stopAllService() }
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            MessageHelper.sendMsg2UI(
                this,
                AppConfig.MSG_STATE_START_FAILURE,
                "VPN interface was not initialized"
            )
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    /** Establishes TUN only after the Psiphon bootstrap has reported CONNECTED. */
    fun startVpnAfterPsiphon() {
        if ((::mInterface.isInitialized && mInterface.fileDescriptor.valid()) || !CoreServiceManager.isRunning()) return
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Psiphon ready; establishing VPN interface")
        PingNgDiagnostics.record("Psiphon ready; establishing Android VPN interface")
        if (!setupVpnService()) return
        if (!CoreServiceManager.promoteBootstrappedCoreToVpn(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to promote Psiphon bootstrap to VPN")
            stopAllService()
            return
        }
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        startupCancelled.set(true)
        WarpMasqueBridge.cancelStartup()
        MasterDnsBridge.cancelStartup()
        startupJob?.cancel()
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        // Stop a previous HEV instance before creating/reusing the Android TUN for AmneziaWG.
        // Otherwise the native HEV singleton can still own a recycled descriptor number when
        // awgTurnOn receives the new interface.
        val selectedProfile = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG) {
            tun2SocksService?.stopTun2Socks()
            tun2SocksService = null
        }
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "VPN permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        // AmneziaWG's Go engine consumes the Android TUN directly. Starting HEV
        // here would make two native engines read/own the same descriptor and
        // can crash libwg-go before awgTurnOn returns. Keep tun2socks for the
        // cores that actually need the userspace proxy path.
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG && !selectedProfile.psiphonEnabled) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: AmneziaWG selected; HEV startup skipped")
        } else {
            runTun2socks()
        }
        return true
    }

    private fun shouldBootstrapPsiphon(): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        return profile.psiphonEnabled
    }

    private fun prepareWarpMasqueRegistration() {
        val guid = MmkvManager.getSelectServer() ?: return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (profile.configType != com.v2ray.ang.enums.EConfigType.WARP) return
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: registering WARP MASQUE before VPN setup")
        WarpRegistrationProxy.prepareMasqueRegistration(
            service = this,
            targetGuid = guid,
            targetProfile = profile,
            proxyGuid = profile.warpRegistrationProxyGuid ?: WarpRegistrationProxy.AUTO,
        )
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // The official AmneziaWG backend clears any stale underlying network before establishing
        // its TUN. This prevents Android from binding the native engine to a previous VPN/network
        // instance after a reconnect or always-on VPN handoff.
        val selectedProfile = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            selectedProfile?.configType == EConfigType.AMNEZIAWG
        ) {
            setUnderlyingNetworks(null)
        }

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Android VPN interface established")
            return true
        } catch (e: Throwable) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            MessageHelper.sendMsg2UI(
                this,
                AppConfig.MSG_STATE_START_FAILURE,
                e.message ?: e.javaClass.simpleName
            )
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val selectedProfile = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG && !selectedProfile.psiphonEnabled) {
            val text = selectedProfile.amneziawgConfig
                ?: error("فایل کانفیگ AmneziaWG در پروفایل پیدا نشد")
            val awg = AmneziaWgFmt.readConfig(text)
            val addresses = awg.addresses
            require(addresses.isNotEmpty()) { "Address در کانفیگ AmneziaWG پیدا نشد" }
            builder.setMtu(awg.mtu ?: 1280)
            addresses.forEach { cidr ->
                val (address, prefix) = splitCidr(cidr, "Address")
                builder.addAddress(address, prefix)
            }
            val routes = awg.routes.distinct()
            require(routes.isNotEmpty()) { "AllowedIPs در کانفیگ AmneziaWG پیدا نشد" }
            routes.forEach { cidr ->
                val (address, prefix) = splitCidr(cidr, "AllowedIPs")
                builder.addRoute(address, prefix)
            }
            val hasSinglePeerDefaultRoute = awg.peers.size == 1 && routes.any {
                it.substringAfterLast('/', missingDelimiterValue = "-1") == "0"
            }
            if (!hasSinglePeerDefaultRoute) {
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowFamily(OsConstants.AF_INET6)
            }
            val dns = awg.dnsServers.ifEmpty { SettingsManager.getVpnDnsServers() }
            dns.forEach { server ->
                if (Utils.isPureIpAddress(server)) builder.addDnsServer(server)
                else builder.addSearchDomain(server)
            }
            builder.setSession(selectedProfile.remarks)
            builder.setBlocking(true)
            PingNgDiagnostics.record("AmneziaWG VPN routes configured from profile AllowedIPs")
            return
        }

        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        val vpnDnsServers = if (selectedProfile?.configType == EConfigType.AMNEZIAWG && selectedProfile.psiphonEnabled) {
            AmneziaWgFmt.readConfig(selectedProfile.amneziawgConfig.orEmpty()).dnsServers
                .filter(Utils::isPureIpAddress).ifEmpty { SettingsManager.getVpnDnsServers() }
        } else SettingsManager.getVpnDnsServers()
        vpnDnsServers.forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    private fun splitCidr(value: String, field: String): Pair<String, Int> {
        val parts = value.trim().split('/', limit = 2)
        require(parts.size == 2 && Utils.isPureIpAddress(parts[0])) {
            "$field باید یک آدرس IP با prefix معتبر باشد: $value"
        }
        val maxPrefix = if (parts[0].contains(':')) 128 else 32
        val prefix = parts[1].toIntOrNull()
        require(prefix != null && prefix in 0..maxPrefix) {
            "$field دارای prefix نامعتبر است: $value"
        }
        return parts[0] to prefix
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            val selectedProfile = MmkvManager.getSelectServer()
                ?.let(MmkvManager::decodeServerConfig)
            val isAmneziaWgTunnel = selectedProfile?.configType == EConfigType.AMNEZIAWG
            val isWarpFullDevice = selectedProfile != null && (
                selectedProfile.configType == com.v2ray.ang.enums.EConfigType.WARP ||
                    WarpMasqueConfig.isDescription(selectedProfile.description) ||
                    WarpPlusConfig.isDescription(selectedProfile.description)
                    || MasterDnsBridge.isProfile(selectedProfile) || isAmneziaWgTunnel
                )
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY) && !isWarpFullDevice) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            } else if (isWarpFullDevice && MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                // A stale Android system HTTP proxy can make browsers fail while
                // UDP apps continue to work. WARP already routes the complete
                // device through Xray, so leave browser proxy discovery alone.
                PingNgDiagnostics.record(
                    if (isAmneziaWgTunnel) "AmneziaWG tunnel: Android HTTP proxy bypassed"
                    else "WARP full-device route: Android HTTP proxy bypassed"
                )
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // A WARP profile is a full-device tunnel. With global per-app mode,
        // selecting only Telegram makes the VPN and MASQUE core look healthy
        // while browsers never enter Xray. Keep PingNG itself outside to
        // avoid a loop, but route the rest of the device through WARP.
        val selectedProfile = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG) {
            val perAppProxy = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
            val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            if (!perAppProxy || apps.isNullOrEmpty()) {
                // The Go engine protects its UDP sockets, so PingNG itself can safely
                // use the tunnel for DNS, exit checks, and user-initiated requests.
                return
            }
            val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
            apps.forEach { packageName ->
                try {
                    if (bypassApps) builder.addDisallowedApplication(packageName)
                    else builder.addAllowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app $packageName", e)
                }
            }
            return
        }
        if (selectedProfile != null && (
                selectedProfile.configType == com.v2ray.ang.enums.EConfigType.WARP ||
                    WarpMasqueConfig.isDescription(selectedProfile.description) ||
                    WarpPlusConfig.isDescription(selectedProfile.description) ||
                    MasterDnsBridge.isProfile(selectedProfile)
                )
        ) {
            builder.addDisallowedApplication(selfPackageName)
            PingNgDiagnostics.record("WARP full-device VPN route enabled; per-app selection bypassed")
            return
        }

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        val selectedProfile = MmkvManager.getSelectServer()
            ?.let(MmkvManager::decodeServerConfig)
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG && selectedProfile.psiphonEnabled) {
            val port = CoreServiceManager.amneziaFinalSocksPort()
            check(port in 1..65535) { "AmneziaWG final SOCKS gateway unavailable" }
            check(TProxyService.isNativeAvailable()) { "HEV native tunnel unavailable" }
            tun2SocksService = TProxyService(applicationContext, mInterface, { isRunning },
                { runTun2socks() }, socksPortOverride = port)
        } else if (selectedProfile?.configType == EConfigType.AMNEZIAWG) {
            tun2SocksService?.stopTun2Socks()
            tun2SocksService = null
            PingNgDiagnostics.record("AmneziaWG owns the Android TUN; tun2socks is skipped")
        } else if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        unlockStart()
        isRunning = false

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        CoreServiceManager.stopCoreLoop(this)

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
