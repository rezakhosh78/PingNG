package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.core.WarpMasqueBridge
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpMasqueStartupPolicy
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.OpenVpnCredentialStore
import com.v2ray.ang.handler.OpenVpnCredentials
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val OPENVPN_DIAGNOSTIC_VERBOSITY = 6

@SuppressLint("VpnServicePolicy")
open class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    @Volatile private var openVpnMode = false
    @Volatile private var openVpnProfileLogId = "unknown"
    @Volatile private var openVpnProfile: ProfileItem? = null
    @Volatile private var openVpnConnected = false
    @Volatile private var openVpnStopRequested = false
    @Volatile private var openVpnFailed = false
    @Volatile private var openVpnStopReported = false
    private val openVpnShutdownStarted = AtomicBoolean(false)
    @Volatile private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)
    private val startupCancelled = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startupJob: Job? = null
    @Volatile private var openVpnProbeJob: Job? = null
    @Volatile private var openVpnStopJob: Job? = null

    fun isStartupCancelled(): Boolean = startupCancelled.get()
    fun isOpenVpnStopRequested(): Boolean = openVpnStopRequested

    /** Whether a probe socket must bypass this service's established Android VPN TUN. */
    fun hasEstablishedVpnInterfaceForProbe(): Boolean =
        ::mInterface.isInitialized && runCatching { mInterface.fileDescriptor.valid() }.getOrDefault(false)

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
        if (openVpnMode) {
            stopOpenVpnClient()
        } else {
            startupJob?.cancel()
            stopAllService()
        }
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
        if (openVpnMode) {
            // Do not invoke OpenVPN JNI on Android's main thread. A native
            // connect can be blocked; its service-owned IO stop job releases
            // that call and closes the TUN after it returns.
            stopOpenVpnClient()
            openVpnStopJob?.invokeOnCompletion {
                unlockStart()
                serviceScope.cancel()
            }
        } else {
            startupJob?.cancel()
            // Startup can be stopped after Android has established the TUN but
            // before CoreServiceManager marks the service as running.
            if (isRunning || (::mInterface.isInitialized && mInterface.fileDescriptor.valid()) || CoreServiceManager.isRunning()) {
                stopAllService()
            }
            serviceScope.cancel()
        }
        super.onDestroy()
        if (!openVpnMode) unlockStart()
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
            openVpnMode = selectedConfigType == EConfigType.OPENVPN
            openVpnConnected = false
            openVpnProfile = null
            openVpnStopRequested = false
            openVpnFailed = false
            openVpnStopReported = false
            openVpnShutdownStarted.set(false)
            openVpnStopJob = null
            openVpnProbeJob?.cancel()
            openVpnProbeJob = null
            if (openVpnMode) {
                CoreServiceManager.setOpenVpnActive(false)
                MmkvManager.setCoreServiceStatus(false)
                CoreServiceManager.registerOpenVpnServiceMessages(this)
            }
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
                    if (selectedConfigType == EConfigType.OPENVPN) {
                        runOpenVpnClient()
                        return@launch
                    }
                    val bootstrapSecondLayer = shouldBootstrapSecondLayer()
                    if (WarpMasqueStartupPolicy.shouldRegisterBeforeSecondLayer(
                            selectedConfigType,
                            bootstrapSecondLayer,
                        )
                    ) {
                        // This early bootstrap path used to skip the selected Proxy and let the
                        // MASQUE core register directly. Prepare credentials before starting it.
                        prepareWarpMasqueRegistration()
                    }
                    if (bootstrapSecondLayer) {
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Holding VPN until the selected second layer connects")
                        PingNgDiagnostics.record("VPN held until the selected second layer connects")
                        if (!CoreServiceManager.isRunning() && !CoreServiceManager.startCoreLoop(null)) {
                            if (!startupCancelled.get()) {
                                unlockStart()
                                stopSelf()
                            }
                            return@launch
                        }
                        return@launch
                    }
                    if (!bootstrapSecondLayer) prepareWarpMasqueRegistration()
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
            job.invokeOnCompletion {
                if (startupJob === job) startupJob = null
                if (openVpnMode && !openVpnShutdownStarted.get()) unlockStart()
            }
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

    /** Establishes TUN only after Tor has established its first circuit. */
    fun startVpnAfterTor() {
        if ((::mInterface.isInitialized && mInterface.fileDescriptor.valid()) || !CoreServiceManager.isRunning()) return
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Tor ready; establishing VPN interface")
        PingNgDiagnostics.record("Tor ready; establishing Android VPN interface")
        if (!setupVpnService()) return
        if (!CoreServiceManager.promoteBootstrappedCoreToVpn(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to promote Tor bootstrap to VPN")
            stopAllService()
            return
        }
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        startupCancelled.set(true)
        WarpMasqueBridge.cancelStartup()
        MasterDnsBridge.cancelStartup()
        if (openVpnMode) {
            stopOpenVpnClient()
            return
        }
        startupJob?.cancel()
        stopAllService(true)
    }

    private fun requestOpenVpnStop() {
        if (!openVpnMode) return
        openVpnStopRequested = true
        openVpnConnected = false
        CoreServiceManager.setOpenVpnActive(false)
        MmkvManager.setCoreServiceStatus(false)
    }

    private fun stopOpenVpnClient() {
        if (!openVpnMode) {
            stopAllService(true)
            return
        }
        requestOpenVpnStop()
        if (!OpenVpnLifecyclePolicy.shouldStartStopWorker(openVpnShutdownStarted.get()) ||
            !openVpnShutdownStarted.compareAndSet(false, true)
        ) return

        openVpnStopJob = serviceScope.launch(Dispatchers.IO) {
            runCatching { OpenVpn3Native.nativeStop() }
                .onFailure {
                    LogUtil.e(
                        AppConfig.TAG,
                        "OpenVPN stop failed during teardown profile=$openVpnProfileLogId",
                        it,
                    )
                }
            val connectJob = startupJob
            if (connectJob != null && connectJob !== openVpnStopJob) {
                withTimeoutOrNull(12_000L) { connectJob.join() }
            }
            stopAllService(true)
        }
    }

    /** Runs both the automatic post-connect check and the Connected-bar action. */
    fun testOpenVpnConnection(): Job? {
        if (!OpenVpnLifecyclePolicy.mayProbe(openVpnConnected, openVpnStopRequested)) return null
        val guid = MmkvManager.getSelectServer() ?: return null
        val profile = MmkvManager.decodeServerConfig(guid)
            ?.takeIf { it.configType == EConfigType.OPENVPN }
            ?: return null

        openVpnProbeJob?.cancel()
        return serviceScope.launch(Dispatchers.IO) {
            val urls = SpeedtestManager.delayProbeUrls(
                SettingsManager.getDelayTestUrl(),
                SettingsManager.getDelayTestUrl(true),
                SpeedtestManager.getRemoteIpInfoUrl(),
            )
            val delay = coroutineScope {
                val probes = urls.map { url ->
                    async(Dispatchers.IO) { SpeedtestManager.directTunnelDelay(url, timeoutMs = 5_000) }
                }
                try {
                    probes.awaitAll().firstOrNull { it >= 0L } ?: -1L
                } finally {
                    probes.forEach { it.cancel() }
                }
            }
            if (!OpenVpnLifecyclePolicy.mayProbe(openVpnConnected, openVpnStopRequested) ||
                guid != MmkvManager.getSelectServer()
            ) return@launch

            // Keep the IP lookup independent from the configured delay URL. A
            // network may block one test endpoint while the tunnel still works.
            val endpoint = runCatching { SpeedtestManager.getRemoteIPInfoDirect() }
                .onFailure { LogUtil.w(AppConfig.TAG, "OpenVPN exit-IP probe failed profile=$openVpnProfileLogId", it) }
                .getOrNull()
            if (!OpenVpnLifecyclePolicy.mayProbe(openVpnConnected, openVpnStopRequested) ||
                guid != MmkvManager.getSelectServer()
            ) return@launch

            val detectedCountryCode = endpoint?.countryCode?.trim()?.uppercase()
                ?.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
            val detectedIpAddress = endpoint?.ipAddress?.trim()?.takeIf(String::isNotEmpty)
            if (detectedCountryCode != null) profile.lastExitCountryCode = detectedCountryCode
            if (detectedIpAddress != null) profile.lastExitIpAddress = detectedIpAddress
            if (detectedCountryCode != null || detectedIpAddress != null) {
                MmkvManager.encodeServerConfig(guid, profile)
            }
            // Preserve the last verified exit details through temporary IP API failures,
            // matching the shared Xray connection-test behavior.
            val countryCode = detectedCountryCode ?: profile.lastExitCountryCode
            val ipAddress = detectedIpAddress ?: profile.lastExitIpAddress
            val errorMessage = if (delay < 0L && ipAddress.isNullOrBlank()) {
                getString(R.string.connection_test_fail)
            } else {
                ""
            }
            MessageHelper.sendMsg2UI(
                this@CoreVpnService,
                AppConfig.MSG_MEASURE_DELAY_RESULT,
                ConnectionTestResult(
                    delayMillis = delay,
                    errorMessage = errorMessage,
                    country = endpoint?.country ?: countryCode,
                    countryCode = countryCode,
                    ipAddress = ipAddress,
                    profileGuid = guid,
                ),
            )
        }.also { openVpnProbeJob = it }
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    /** OpenVPN 3 asks Android to keep its transport socket outside this VPN tunnel. */
    @Keep
    fun protectOpenVpnSocket(socket: Int): Boolean {
        return try {
            val protected = protect(socket)
            if (protected) {
                LogUtil.i(AppConfig.TAG, "OpenVPN startup profile=$openVpnProfileLogId transport socket protected")
            } else {
                LogUtil.e(AppConfig.TAG, "OpenVPN startup profile=$openVpnProfileLogId could not protect transport socket")
            }
            protected
        } catch (error: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "OpenVPN startup profile=$openVpnProfileLogId transport socket protection threw an exception",
                error,
            )
            false
        }
    }

    /** Establishes Android's TUN after OpenVPN 3 has collected pushed addresses and routes. */
    @Keep
    fun establishOpenVpnTunnel(
        addresses: Array<String>,
        routes: Array<String>,
        excludedRoutes: Array<String>,
        dnsServers: Array<String>,
        searchDomains: Array<String>,
        allowedFamilies: IntArray,
        mtu: Int,
        sessionName: String,
    ): Int {
        if (!OpenVpnLifecyclePolicy.mayContinueStartup(startupCancelled.get(), openVpnStopRequested) ||
            !openVpnMode
        ) return -1
        var established: ParcelFileDescriptor? = null
        return try {
            val builder = Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            if (mtu in 576..65535) builder.setMtu(mtu)
            addresses.distinct().forEach { cidr ->
                val (address, prefix) = splitCidr(cidr, "OpenVPN address")
                builder.addAddress(address, prefix)
            }
            require(addresses.isNotEmpty()) { "OpenVPN server did not assign a tunnel address" }
            routes.distinct().forEach { cidr ->
                val (address, prefix) = splitCidr(cidr, "OpenVPN route")
                builder.addRoute(address, prefix)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                excludedRoutes.distinct().forEach { cidr ->
                    val (address, prefix) = splitCidr(cidr, "OpenVPN excluded route")
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
                }
            }
            dnsServers.distinct().filter { Utils.isPureIpAddress(it) }
                .forEach { builder.addDnsServer(it) }
            searchDomains.distinct().filter(String::isNotBlank)
                .forEach { builder.addSearchDomain(it) }
            allowedFamilies.distinct().forEach { family ->
                if (family == OsConstants.AF_INET || family == OsConstants.AF_INET6) {
                    builder.allowFamily(family)
                }
            }
            if (sessionName.isNotBlank()) builder.setSession(sessionName.take(80))
            configurePerAppProxy(builder)

            if (::mInterface.isInitialized && mInterface.fileDescriptor.valid()) {
                mInterface.close()
            }
            established = builder.establish()
                ?: error("Android could not establish the OpenVPN tunnel")
            val nativeTun = established!!.dup()
            mInterface = established!!
            isRunning = true
            PingNgDiagnostics.record("OpenVPN 3 Android tunnel established")
            nativeTun.detachFd()
        } catch (error: Throwable) {
            runCatching { established?.close() }
            LogUtil.e(AppConfig.TAG, "OpenVPN 3 could not establish its Android tunnel", error)
            -1
        }
    }

    /** Reports OpenVPN 3 state changes without writing profile secrets to logs. */
    @Keep
    fun onOpenVpnEvent(name: String, error: Boolean, fatal: Boolean, info: String) {
        if (name == "CONNECTED") {
            if (openVpnStopRequested || startupCancelled.get()) return
            openVpnConnected = true
            isRunning = true
            CoreServiceManager.setOpenVpnActive(true)
            MmkvManager.setCoreServiceStatus(true)
            openVpnProfile?.let { NotificationManager.showNotification(it) }
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_SUCCESS, "")
            testOpenVpnConnection()
            return
        }
        val safeInfo = info.trim().replace(Regex("\\s+"), " ").take(800)
        if (error && fatal) {
            openVpnFailed = true
            isRunning = false
            CoreServiceManager.setOpenVpnActive(false)
            MmkvManager.setCoreServiceStatus(false)
            val reason = safeInfo.ifBlank { name }
            LogUtil.e(AppConfig.TAG, "OpenVPN event $name: $reason")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, reason)
        } else if (error) {
            LogUtil.w(AppConfig.TAG, "OpenVPN event $name: ${safeInfo.ifBlank { "no details" }}")
        } else {
            LogUtil.i(AppConfig.TAG, "OpenVPN event: $name")
        }
    }

    /** OpenVPN core diagnostics are useful for troubleshooting; credentials are never passed here. */
    @Keep
    fun onOpenVpnLog(line: String) {
        LogUtil.i(AppConfig.TAG, "OpenVPN: ${line.take(800)}")
    }

    private fun runOpenVpnClient() {
        val guid = MmkvManager.getSelectServer() ?: error("No OpenVPN profile is selected")
        openVpnProfileLogId = guid.take(8)
        val profile = MmkvManager.decodeServerConfig(guid)
            ?.takeIf { it.configType == EConfigType.OPENVPN }
            ?: error("The selected OpenVPN profile could not be loaded")
        openVpnProfile = profile
        val config = profile.openVpnConfig.orEmpty()
        require(config.isNotBlank()) { "OpenVPN profile is empty" }
        val requirements = OpenVpnProfileCodec.credentialRequirements(config)
        val credentials = OpenVpnCredentialStore.load(this, guid) ?: OpenVpnCredentials()
        if ((requirements.usernamePassword &&
                (credentials.username.isBlank() || credentials.password.isBlank())) ||
            (requirements.privateKeyPassphrase && credentials.privateKeyPassphrase.isBlank())
        ) {
            throw IllegalStateException(getString(com.v2ray.ang.R.string.openvpn_credentials_required))
        }
        check(OpenVpnLifecyclePolicy.mayContinueStartup(startupCancelled.get(), openVpnStopRequested)) {
            "OpenVPN startup canceled"
        }
        // This ClientAPI receives verbosity through profile content, so make sure the
        // diagnostic level reaches the core even if a stale profile row still says verb 3.
        val runtimeConfig = OpenVpnProfileCodec.runtimeConfig(
            config,
            forcedVerbosity = OPENVPN_DIAGNOSTIC_VERBOSITY,
        )
        val storedVerbosity = OpenVpnProfileCodec.verbosity(config)
        val runtimeVerbosity = OpenVpnProfileCodec.verbosity(runtimeConfig)
        LogUtil.i(
            AppConfig.TAG,
            "OpenVPN 3 starting profile=$openVpnProfileLogId " +
                "storedVerb=${storedVerbosity ?: "default"} runtimeVerb=${runtimeVerbosity ?: "default"} " +
                "profileBytes=${runtimeConfig.toByteArray(Charsets.UTF_8).size}",
        )
        PingNgDiagnostics.record("Starting OpenVPN 3 client")
        val failure = OpenVpn3Native.nativeRun(
            this,
            runtimeConfig,
            credentials.username,
            credentials.password,
            credentials.privateKeyPassphrase,
        )
        if (openVpnStopRequested || startupCancelled.get()) {
            LogUtil.i(AppConfig.TAG, "OpenVPN client stopped by request")
            return
        }
        if (!openVpnFailed) {
            val reason = failure.trim().replace(Regex("\\s+"), " ").takeIf(String::isNotBlank)
                ?: if (openVpnConnected) "OpenVPN disconnected" else "OpenVPN stopped before connecting"
            openVpnFailed = true
            LogUtil.e(AppConfig.TAG, "OpenVPN profile=$openVpnProfileLogId client ended: ${reason.take(800)}")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, reason.take(800))
        }
        stopAllService()
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
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG &&
            !selectedProfile.psiphonEnabled && !selectedProfile.torEnabled
        ) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: AmneziaWG selected; HEV startup skipped")
        } else {
            runTun2socks()
        }
        return true
    }

    private fun shouldBootstrapSecondLayer(): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        return profile.psiphonEnabled || profile.torEnabled
    }

    private suspend fun prepareWarpMasqueRegistration() {
        val guid = MmkvManager.getSelectServer() ?: return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (profile.configType != com.v2ray.ang.enums.EConfigType.WARP) return
        if (!com.v2ray.ang.core.WarpMasqueConfig.isDescription(profile.description)) return
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
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG &&
            !selectedProfile.psiphonEnabled && !selectedProfile.torEnabled
        ) {
            val text = selectedProfile.amneziawgConfig
                ?: error("AmneziaWG configuration was not found in the profile")
            val awg = AmneziaWgFmt.readConfig(text)
            val addresses = awg.addresses
            require(addresses.isNotEmpty()) { "Address is missing from the AmneziaWG configuration" }
            builder.setMtu(awg.mtu ?: 1280)
            addresses.forEach { cidr ->
                val (address, prefix) = splitCidr(cidr, "Address")
                builder.addAddress(address, prefix)
            }
            val routes = awg.routes.distinct()
            require(routes.isNotEmpty()) { "AllowedIPs is missing from the AmneziaWG configuration" }
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
            val dns = AmneziaWgFmt.effectiveDnsServers(
                profileDns = awg.dnsServers,
                fallbackDns = SettingsManager.getVpnDnsServers(),
            )
            dns.forEach { server ->
                builder.addDnsServer(server)
            }
            awg.dnsServers.filterNot(Utils::isPureIpAddress).forEach { builder.addSearchDomain(it) }
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
        val vpnDnsServers = if (selectedProfile?.configType == EConfigType.AMNEZIAWG) {
            AmneziaWgFmt.effectiveDnsServers(
                profileDns = AmneziaWgFmt.readConfig(selectedProfile.amneziawgConfig.orEmpty()).dnsServers,
                fallbackDns = SettingsManager.getVpnDnsServers(),
            )
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
            "$field must be an IP address with a valid prefix: $value"
        }
        val maxPrefix = if (parts[0].contains(':')) 128 else 32
        val prefix = parts[1].toIntOrNull()
        require(prefix != null && prefix in 0..maxPrefix) {
            "$field has an invalid prefix: $value"
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
     * OpenVPN keeps PingNG in the tunnel for direct status and latency checks.
     * Xray-based profiles retain the configured per-app proxy behavior below.
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
        if (selectedProfile?.configType == EConfigType.OPENVPN) {
            val perAppProxy = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
            val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            if (!perAppProxy || apps.isNullOrEmpty()) {
                // OpenVPN protects its own transport sockets. Keep PingNG in the
                // tunnel so connection checks and the app's direct route use the
                // same egress being shown as connected.
                PingNgDiagnostics.record("OpenVPN route includes PingNG for tunnel checks")
                return
            }

            val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
            val routedApps = if (bypassApps) apps.filterNot { it == selfPackageName }
            else (apps + selfPackageName).distinct()
            routedApps.forEach { packageName ->
                try {
                    if (bypassApps) builder.addDisallowedApplication(packageName)
                    else builder.addAllowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app $packageName", e)
                }
            }
            PingNgDiagnostics.record("OpenVPN per-app rules include PingNG for tunnel checks")
            return
        }
        if (selectedProfile?.torEnabled == true) {
            // Every Tor Over profile captures the whole device. The AWG
            // engine protects its transport sockets from this VPN itself.
            if (selectedProfile.configType != EConfigType.AMNEZIAWG) {
                builder.addDisallowedApplication(selfPackageName)
            }
            PingNgDiagnostics.record("Tor Over profile: full-device VPN route enabled")
            return
        }
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG) {
            if (selectedProfile.psiphonEnabled) {
                // Keep all device traffic in the AWG route so the AWG exit IP
                // remains consistent, including QUIC and other UDP traffic.
                builder.addDisallowedApplication(selfPackageName)
                PingNgDiagnostics.record(
                    "Psiphon Over AmneziaWG full-device route enabled; per-app selection bypassed"
                )
                return
            }
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
        if (selectedProfile?.configType == EConfigType.AMNEZIAWG &&
            (selectedProfile.psiphonEnabled || selectedProfile.torEnabled)
        ) {
            val port = CoreServiceManager.amneziaFinalSocksPort()
            check(port in 1..65535) { "AmneziaWG second-layer SOCKS gateway unavailable" }
            check(TProxyService.isNativeAvailable()) { "HEV native tunnel unavailable" }
            // The gateway routes user TCP and UDP through AmneziaWG, preserving
            // the WARP/AWG exit IP. Psiphon is used only for its bootstrap path.
            if (selectedProfile.psiphonEnabled) {
                PingNgDiagnostics.record(
                    "Psiphon bootstrap -> AmneziaWG; user TCP/UDP (including QUIC) -> AmneziaWG exit"
                )
            }
            tun2SocksService = TProxyService(applicationContext, mInterface, { isRunning },
                { runTun2socks() }, socksPortOverride = port,
                udpDnsOnly = false)
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
        if (!openVpnMode) unlockStart()
        isRunning = false
        if (openVpnMode) {
            CoreServiceManager.setOpenVpnActive(false)
            MmkvManager.setCoreServiceStatus(false)
            openVpnProbeJob?.cancel()
            CoreServiceManager.unregisterOpenVpnServiceMessages(this)
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        if (!openVpnMode) CoreServiceManager.stopCoreLoop(this)

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
            if (!openVpnMode) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
                }
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
            if (openVpnMode && openVpnStopRequested && !openVpnFailed && !openVpnStopReported) {
                openVpnStopReported = true
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
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
