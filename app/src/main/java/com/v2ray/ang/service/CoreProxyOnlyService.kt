package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.WarpMasqueBridge
import com.v2ray.ang.core.WarpRegistrationProxy
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

        if (intent?.action == LauncherManager.ACTION_RECONFIGURE_DESYNC) {
            if (CoreServiceManager.isRunning()) {
                CoreServiceManager.reconfigureDesync()
            }
            return START_STICKY
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
        startupJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        CoreServiceManager.stopCoreLoop(this)
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
        stopSelf()
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
