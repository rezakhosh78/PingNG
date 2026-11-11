package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.PsiphonBridge
import com.v2ray.ang.core.SoarBridge
import com.v2ray.ang.core.TorBridge
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.SubscriptionNoticeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val QUERY_INTERVAL_MS = 3000L

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null
    @Volatile private var activeLocalTrafficSubscriptionId: String? = null
    @Volatile private var notificationGeneration = 0L
    @Volatile private var notificationActive = false
    @Volatile private var notificationConnected = false

    /** Polls the core traffic counters and updates speed text when that setting is enabled. */
    @Synchronized
    fun startSpeedNotification() {
        if (speedNotificationJob != null || !notificationConnected ||
            !CoreServiceManager.isNotificationConnected()
        ) return

        val generation = notificationGeneration
        var lastZeroSpeed = false

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isCurrentConnectedNotification(generation)) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed, generation)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    @Synchronized
    fun showNotification(currentConfig: ProfileItem?) {
        if (currentConfig != null && CoreServiceManager.isStopping()) return
        val connected = currentConfig != null && CoreServiceManager.isNotificationConnected()
        val activeAmneziaWg = currentConfig?.configType == EConfigType.AMNEZIAWG &&
            CoreServiceManager.isRunning()
        val secondLayerConnecting = activeAmneziaWg && when {
            currentConfig?.psiphonEnabled == true -> !PsiphonBridge.isEgressVerified()
            currentConfig?.torEnabled == true -> !TorBridge.isConnected()
            else -> false
        }
        // Keep the selected AWG profile visible while its chained layer starts or reconnects.
        val visibleConfig = currentConfig.takeIf { connected || activeAmneziaWg }

        notificationGeneration++
        notificationActive = true
        notificationConnected = connected
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        activeLocalTrafficSubscriptionId = visibleConfig?.let(::resolveLocalTrafficSubscriptionId)

        val service = getService() ?: run {
            notificationActive = false
            notificationConnected = false
            mBuilder = null
            return
        }

        // Reset last query time to avoid querying stats too soon after showing the notification
        lastQueryTime = System.currentTimeMillis()

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_pingng_status)
            .setContentTitle(visibleConfig?.remarks?.takeIf { it.isNotBlank() }
                ?: service.getString(R.string.app_name))
            .setContentText(service.getString(
                when {
                    secondLayerConnecting -> R.string.notification_connection_connecting
                    !connected -> R.string.connection_not_connected
                    visibleConfig?.configType == EConfigType.OPENVPN -> R.string.openvpn_notification_connected
                    else -> R.string.connection_connected
                },
            ))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(
                    when {
                        currentConfig?.description == MasterDnsBridge.DNSTT -> R.string.notification_action_reconnect_dnstt
                        visibleConfig?.let(SoarBridge::isProfile) == true -> R.string.notification_action_reconnect_soar
                        else -> R.string.title_service_restart
                    },
                ),
                restartV2RayPendingIntent
            )

        //mBuilder?.setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /**
     * Fulfills or refreshes the foreground-service contract before a start command can
     * return early. A duplicate startForegroundService call still requires the service
     * to enter foreground state promptly, even when the core is already running.
     */
    @Synchronized
    fun ensureForeground() {
        val service = getService() ?: return
        if (mBuilder == null || (notificationConnected && !CoreServiceManager.isNotificationConnected())) {
            showNotification(null)
            return
        }
        val notification = mBuilder?.build()
        if (notification != null) service.startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Cancels the notification.
     */
    @Synchronized
    fun cancelNotification() {
        notificationGeneration++
        notificationActive = false
        notificationConnected = false
        activeLocalTrafficSubscriptionId = null
        val service = getService()
        try {
            if (service != null) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                mNotificationManager?.cancel(NOTIFICATION_ID)
            }
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "Notification removal failed", error)
            mNotificationManager?.cancel(NOTIFICATION_ID)
        } finally {
            mBuilder = null
            speedNotificationJob?.cancel()
            speedNotificationJob = null
            mNotificationManager = null
        }
    }

    /**
     * Stops the speed notification.
     */
    @Synchronized
    fun stopSpeedNotification() {
        val generation = notificationGeneration
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("", 0, 0, generation)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        // Foreground-service notifications must remain visible; LOW is silent but valid.
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        chan.lightColor = Color.DKGRAY
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager()?.createNotificationChannel(chan)
        return channelId
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    @Synchronized
    private fun updateNotification(
        contentText: String?,
        proxyTraffic: Long,
        directTraffic: Long,
        generation: Long,
    ) {
        if (isCurrentConnectedNotification(generation) && mBuilder != null) {
            mBuilder?.setSmallIcon(R.drawable.ic_pingng_status)
            mBuilder?.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            mBuilder?.setContentText(contentText)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    private fun isCurrentConnectedNotification(generation: Long): Boolean =
        notificationActive &&
            notificationConnected &&
            notificationGeneration == generation &&
            CoreServiceManager.isNotificationConnected()

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    private fun resolveLocalTrafficSubscriptionId(config: ProfileItem): String? {
        val subscriptionId = config.subscriptionId.trim().takeIf { it.isNotEmpty() } ?: return null
        val subscriptionUrl = MmkvManager.decodeSubscription(subscriptionId)?.url ?: return null
        return subscriptionId.takeIf {
            SubscriptionNoticeParser.isLocalTrafficGraphSubscription(subscriptionUrl)
        }
    }

    /**
     * Appends the speed string to the given text.
     * @param text The text to append to.
     * @param name The name of the tag.
     * @param up The uplink speed.
     * @param down The downlink speed.
     */
    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.take(min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    /**
     * Updates the speed notification once.
     * Queries traffic stats, separates proxy and direct, and updates the notification.
     * @param lastZeroSpeed The previous zero speed state.
     * @return The current zero speed state.
     */
    private fun updateSpeedNotificationOnce(lastZeroSpeed: Boolean, generation: Long): Boolean {
        if (!isCurrentConnectedNotification(generation)) return lastZeroSpeed
        val queryTime = System.currentTimeMillis()
        val sinceLastQueryIn = (queryTime - lastQueryTime)

        // If the query interval is too short, skip this round to avoid excessive CPU usage
        if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
            LogUtil.w(AppConfig.TAG, "Query interval too short: ${sinceLastQueryIn}ms, skipping")
            lastQueryTime = queryTime
            return lastZeroSpeed
        }
        val sinceLastQueryInSeconds = sinceLastQueryIn / 1000.0

        var proxyUplink = 0L
        var proxyDownlink = 0L
        var directUplink = 0L
        var directDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> directUplink += stat.value
                        AppConfig.DOWNLINK -> directDownlink += stat.value
                    }
                }

                // Accumulate stats for all proxy outbounds (including custom subscription tags)
                stat.tag != AppConfig.TAG_BLOCKED -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> proxyUplink += stat.value
                        AppConfig.DOWNLINK -> proxyDownlink += stat.value
                    }
                }
            }
        }

        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L
        val vpnTrafficTotal = proxyTotal + directTotal
        if (vpnTrafficTotal > 0L) {
            MmkvManager.addDefaultTrafficUsedBytes(vpnTrafficTotal)
            activeLocalTrafficSubscriptionId?.let { subscriptionId ->
                MmkvManager.addLocalSubscriptionTrafficUsedBytes(subscriptionId, vpnTrafficTotal)
            }
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true &&
            (!zeroSpeed || !lastZeroSpeed)
        ) {
            val text = StringBuilder()
            appendSpeedString(
                text, AppConfig.TAG_PROXY,
                proxyUplink / sinceLastQueryInSeconds,
                proxyDownlink / sinceLastQueryInSeconds
            )

            appendSpeedString(
                text, AppConfig.TAG_DIRECT,
                directUplink / sinceLastQueryInSeconds,
                directDownlink / sinceLastQueryInSeconds
            )
            updateNotification(text.toString(), proxyTotal, directTotal, generation)
        }
        lastQueryTime = queryTime
        return zeroSpeed
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
