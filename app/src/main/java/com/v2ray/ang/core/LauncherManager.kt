package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object LauncherManager {
    const val ACTION_RECONFIGURE_DESYNC = "com.v2ray.ang.action.RECONFIGURE_DESYNC"

    fun startServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    fun startService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")
        PingNgDiagnostics.record("UI requested connection")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            PingNgDiagnostics.record("Android service launch failed", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Starts Xray with only its local proxy inbounds for diagnostics/search.
     * This deliberately ignores the user's VPN/root mode so a Desync search
     * never enables the Android VPN interface for every candidate.
     */
    fun startProxyOnlyService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: start proxy-only service for diagnostics")
        PingNgDiagnostics.record("Starting proxy-only Xray for Desync search")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context, forceProxyOnly = true)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: proxy-only start failed", e)
            PingNgDiagnostics.record("Proxy-only service launch failed", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    fun reconfigureProxyOnlyService(context: Context, guid: String? = null) {
        if (guid != null) MmkvManager.setSelectServer(guid)
        val intent = Intent(context.applicationContext, CoreProxyOnlyService::class.java).apply {
            action = ACTION_RECONFIGURE_DESYNC
        }
        runCatching {
            // The proxy-only service is already running in the foreground;
            // deliver a command instead of starting a second service path.
            context.startService(intent)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Desync reconfigure failed", it)
        }
    }

    fun stopService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")

        // The daemon receiver normally performs the graceful teardown. Keep a direct Android
        // stop as a fallback for stale/missed broadcasts; each service's onDestroy now closes
        // its own core/VPN resources safely.
        listOf(
            CoreVpnService::class.java,
            CoreRootService::class.java,
            CoreProxyOnlyService::class.java,
        ).forEach { serviceClass ->
            runCatching {
                context.stopService(Intent(context.applicationContext, serviceClass))
            }
        }
    }

    /** Restarts the active daemon without starting a stopped service. */
    fun restartService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /** Restarts the active daemon, or delegates to the caller's permission-aware start flow. */
    fun restartServiceOrStart(context: Context, startIfStopped: () -> Unit) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
            if (!handled) startIfStopped()
        }
    }

    @Throws(Exception::class)
    private fun startContextService(context: Context, forceProxyOnly: Boolean = false) {
        // Note: isRunning check is removed here to avoid loading Native libraries in the UI process.
        // The check is performed in CoreServiceManager when the service starts in the daemon process.

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        SettingsManager.refreshRuntimeSocksPort()

        if (config.insecure == true && config.pinnedCA256.isNullOrEmpty()) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            Utils.setClipboard(context, context.getString(R.string.toast_allow_insecure_deprecated))
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (!forceProxyOnly && isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (forceProxyOnly) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting proxy-only diagnostic service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        } else if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }
}
