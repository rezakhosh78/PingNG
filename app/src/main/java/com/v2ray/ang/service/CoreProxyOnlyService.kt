package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.SoarBridge
import com.v2ray.ang.core.WarpMasqueBridge
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.util.LogUtil
import java.lang.ref.SoftReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CoreProxyOnlyService : Service(), ServiceControl {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startupJob: Job? = null
    @Volatile private var startupCancelled = false
    private var registrationResultGuid: String? = null
    private var registrationSucceeded = false

    fun isStartupCancelled(): Boolean = startupCancelled

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")

        if (intent?.action == LauncherManager.ACTION_STOP_ACTIVE_SERVICE) {
            stopService()
            return START_NOT_STICKY
        }

        if (intent?.action == LauncherManager.ACTION_RECONFIGURE_DESYNC) {
            if (CoreServiceManager.isRunning()) {
                CoreServiceManager.reconfigureDesync()
            }
            return START_STICKY
        }

        if (intent?.action == LauncherManager.ACTION_PREPARE_MASQUE_REGISTRATION) {
            if (startupJob?.isActive == true) return START_NOT_STICKY
            val guid = intent.getStringExtra("profile_guid") ?: return START_NOT_STICKY
            startupCancelled = false
            startupJob = serviceScope.launch {
                try {
                    val profile = MmkvManager.decodeServerConfig(guid)
                        ?: error("MASQUE/H2 configuration was not found")
                    WarpRegistrationProxy.prepareMasqueRegistration(
                        this@CoreProxyOnlyService, guid, profile,
                        profile.warpRegistrationProxyGuid ?: WarpRegistrationProxy.AUTO)
                    if (!startupCancelled) {
                        registrationResultGuid = guid
                        registrationSucceeded = true
                    }
                } catch (error: Throwable) {
                    if (!startupCancelled) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Proxy: MASQUE registration failed for $guid", error)
                        registrationResultGuid = guid
                        registrationSucceeded = false
                    }
                } finally {
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }

        if (CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Core is already running")
            return START_STICKY
        }
        if (startupJob?.isActive == true) return START_STICKY
        startupCancelled = false
        startupJob = serviceScope.launch {
            try {
                WarpRegistrationProxy.prepareSelectedMasqueRegistration(this@CoreProxyOnlyService)
                if (startupCancelled) return@launch
                if (!CoreServiceManager.startCoreLoop(null)) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
                    stopSelf()
                }
            } catch (error: Throwable) {
                LogUtil.e(AppConfig.TAG, "StartCore-Proxy: WARP MASQUE startup failed", error)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        startupCancelled = true
        WarpMasqueBridge.cancelStartup()
        MasterDnsBridge.cancelStartup()
        SoarBridge.cancelStartup()
        startupJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        CoreServiceManager.stopCoreLoop(this)
        registrationResultGuid?.let { guid ->
            MessageHelper.sendMsg2UI(this,
                if (registrationSucceeded) AppConfig.MSG_MASQUE_REGISTRATION_READY
                else AppConfig.MSG_MASQUE_REGISTRATION_FAILED, guid)
        }
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        startupCancelled = true
        WarpMasqueBridge.cancelStartup()
        MasterDnsBridge.cancelStartup()
        SoarBridge.cancelStartup()
        val connectJob = startupJob
        connectJob?.cancel()
        serviceScope.launch {
            connectJob?.join()
            stopSelf()
        }
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}
