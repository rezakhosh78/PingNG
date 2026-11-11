package com.rkh.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rkh.vpn.data.MspPrivacy
import com.v2ray.ang.R
import com.v2ray.ang.ui.main.MainActivity

/** PingNG notification adapter for the unchanged SIMORGH MSP service. */
object NotificationHelper {
    private const val CHANNEL = "pingng_msp_status"

    fun publicVpn(context: Context, title: String, text: String, connected: Boolean = true) =
        build(context, title, text, connected)

    private fun build(context: Context, title: String, text: String, connected: Boolean) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pingng_status)
            .setContentTitle(title)
            .setContentText(MspPrivacy.redact(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(MspPrivacy.redact(text)))
            .setSubText(if (connected) "MSP active" else "MSP stopped")
            .setOngoing(connected)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context, 2101))
            .addAction(
                R.drawable.ic_stop_24dp,
                "Disconnect",
                stopIntent(context),
            )
            .build()

    private fun stopIntent(context: Context): PendingIntent {
        ensureChannel(context)
        val intent = Intent(context, SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_STOP)
            .putExtra(SimorghPublicVpnService.EXTRA_PRE_CONNECT_RESET, true)
        return PendingIntent.getService(
            context,
            2102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "MSP status", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
