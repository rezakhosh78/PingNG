package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.helper.NotificationHelper

/** Keeps the app process foreground while Find Desync runs in the background. */
class DesyncSearchKeepAliveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.DESYNC_SEARCH,
            getString(R.string.app_name),
            getString(R.string.pingng_desync_searching),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
