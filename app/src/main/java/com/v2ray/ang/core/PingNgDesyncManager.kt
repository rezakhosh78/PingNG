package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/** Runs the embedded rootless PingNG engine in the daemon process. */
object PingNgDesyncManager {
    const val OUTBOUND_TAG = "pingng-desync"

    @Volatile
    private var port: Int = 0
    private var engine: PingNGProxy? = null
    private var worker: Thread? = null

    fun activePort(): Int = port

    @Synchronized
    fun start(profile: ProfileItem): Int {
        stopLocked()
        if (!PingNgCompat.isNativeDesyncEnabled(profile)) {
            PingNgDiagnostics.record("Desync is Off for ${profile.remarks}")
            return 0
        }

        val selectedPort = Utils.findRandomFreePort()
        val command = PingNgCompat.buildCommandLine(profile, selectedPort)
            ?: error("No Android Desync command is available for ${profile.pingNgProfile}")
        PingNgDiagnostics.record("Preparing ${profile.pingNgProfile}: ${command.joinToString(" ")}")
        val proxy = PingNGProxy()
        try {
            proxy.prepare(command.toTypedArray())
        } catch (error: Throwable) {
            PingNgDiagnostics.record("Native PingNG rejected the command", error)
            throw error
        }

        engine = proxy
        port = selectedPort
        worker = Thread({
            val result = try {
                proxy.runLoop()
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "PingNG Desync engine failed", e)
                PingNgDiagnostics.record("Native PingNG loop failed", e)
                -1
            }
            synchronized(this) {
                if (engine === proxy) {
                    engine = null
                    worker = null
                    port = 0
                }
            }
            if (result != 0) {
                LogUtil.e(AppConfig.TAG, "PingNG Desync engine stopped with code $result")
                PingNgDiagnostics.record("Native PingNG stopped with code $result")
            }
        }, "PingNG-PingNG").apply {
            isDaemon = true
            start()
        }

        LogUtil.i(AppConfig.TAG, "PingNG Desync started on 127.0.0.1:$selectedPort (${profile.pingNgProfile})")
        PingNgDiagnostics.record("Desync started on 127.0.0.1:$selectedPort")
        return selectedPort
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val proxy = engine
        engine = null
        worker = null
        port = 0
        if (proxy != null) {
            try {
                proxy.stop()
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "Failed to stop PingNG Desync engine", e)
                PingNgDiagnostics.record("Failed to stop native PingNG", e)
            }
            PingNgDiagnostics.record("Desync stopped")
        }
    }
}