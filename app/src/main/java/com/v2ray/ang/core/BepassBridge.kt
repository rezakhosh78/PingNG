package com.v2ray.ang.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.service.BepassRuntimeService
import com.v2ray.ang.util.Utils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Starts and routes a profile through the isolated local Bepass SOCKS runtime. */
object BepassBridge {
    private const val START_TIMEOUT_SECONDS = 20L

    @Volatile private var activeGuid: String? = null
    @Volatile private var activeSettingsJson: String? = null
    @Volatile private var activePort = 0
    @Volatile private var pendingGuid: String? = null
    private var generation = 0L
    private val stateLock = Any()
    private val startLock = ReentrantLock()

    fun start(context: Context, guid: String, profile: ProfileItem, forceRestart: Boolean = false): Int =
        startLock.withLock {
        if (!profile.bepassEnabled) {
            stop(context, force = true)
            return@withLock 0
        }
        check(BepassSettings.isSupported(profile)) {
            "Bepass supports only VMess, VLESS, Trojan, or Shadowsocks with TCP TLS/REALITY"
        }
        check(!PingNgCompat.isNativeDesyncEnabled(profile)) {
            "Bepass cannot be combined with PingNG Desync"
        }
        check(!BepassSettings.hasTcpFinalMask(profile)) {
            "Bepass cannot be combined with a TCP FinalMask on this profile"
        }
        val settings = BepassSettings.fromJson(profile.bepassSettingsJson)
        check(settings.validate() == null) { "Bepass settings are invalid: ${settings.validate()}" }
        val settingsJson = settings.toJson()
        val currentPort = synchronized(stateLock) {
            activePort.takeIf {
                !forceRestart && activeGuid == guid && activeSettingsJson == settingsJson && it in 1..65535
            } ?: 0
        }
        if (currentPort > 0) return@withLock currentPort

        val (startGeneration, previousGuid) = synchronized(stateLock) {
            generation += 1
            val previous = activeGuid ?: pendingGuid
            activeGuid = null
            activeSettingsJson = null
            activePort = 0
            pendingGuid = guid
            generation to previous
        }
        if (!previousGuid.isNullOrBlank()) sendStop(context, previousGuid)
        val port = Utils.findRandomFreePort()
        val configJson = settings.toRuntimeJson(port)
        val result = try {
            awaitRuntimeEvent(context, guid) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BepassRuntimeService::class.java)
                        .setAction(AppConfig.BEPASS_RUNTIME_START)
                        .putExtra(AppConfig.EXTRA_BEPASS_GUID, guid)
                        .putExtra(AppConfig.EXTRA_BEPASS_CONFIG, configJson),
                )
            }
        } catch (error: Throwable) {
            sendStop(context, guid)
            synchronized(stateLock) {
                if (generation == startGeneration) pendingGuid = null
            }
            throw error
        }
        if (result.first != AppConfig.BEPASS_RUNTIME_READY || result.second != port) {
            sendStop(context, guid)
            synchronized(stateLock) {
                if (generation == startGeneration) pendingGuid = null
            }
            error(result.third.ifBlank { "Bepass SOCKS listener did not start" })
        }
        synchronized(stateLock) {
            check(generation == startGeneration && pendingGuid == guid) {
                "Bepass startup was cancelled"
            }
            activeGuid = guid
            activeSettingsJson = settingsJson
            activePort = port
            pendingGuid = null
        }
        port
    }

    /** Adds the loopback SOCKS outbound and makes the primary TLS stream dial through it. */
    fun prepareCoreConfig(guid: String, profile: ProfileItem, raw: String): String {
        if (!profile.bepassEnabled) return raw
        val port = activePort.takeIf { activeGuid == guid && it in 1..65535 }
            ?: error("Bepass was enabled but its SOCKS runtime is not ready")
        val config = JsonParser.parseString(raw).asJsonObject
        check(BepassConfigPolicy.attach(config, profile, port)) { "Bepass routing was not applied" }
        return config.toString()
    }

    fun stop(context: Context, force: Boolean = false) {
        val guid = synchronized(stateLock) {
            generation += 1
            val stoppingGuid = activeGuid ?: pendingGuid
            activeGuid = null
            activeSettingsJson = null
            activePort = 0
            pendingGuid = null
            stoppingGuid
        }
        if (guid.isNullOrBlank()) {
            if (force) sendStop(context, "")
            return
        }
        sendStop(context, guid.orEmpty())
    }

    private fun sendStop(context: Context, guid: String) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BepassRuntimeService::class.java)
                    .setAction(AppConfig.BEPASS_RUNTIME_STOP)
                    .putExtra(AppConfig.EXTRA_BEPASS_GUID, guid),
            )
        }
    }

    private fun awaitRuntimeEvent(
        context: Context,
        guid: String,
        start: () -> Unit,
    ): Triple<String, Int, String> {
        val latch = CountDownLatch(1)
        var state = ""
        var port = 0
        var message = ""
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AppConfig.ACTION_BEPASS_RUNTIME ||
                    intent.getStringExtra(AppConfig.EXTRA_BEPASS_GUID) != guid
                ) return
                val incomingState = intent.getStringExtra(AppConfig.EXTRA_BEPASS_STATE).orEmpty()
                if (incomingState != AppConfig.BEPASS_RUNTIME_READY &&
                    incomingState != AppConfig.BEPASS_RUNTIME_FAILED &&
                    incomingState != AppConfig.BEPASS_RUNTIME_STOPPED
                ) return
                state = incomingState
                port = intent.getIntExtra(AppConfig.EXTRA_BEPASS_SOCKS_PORT, 0)
                message = intent.getStringExtra(AppConfig.EXTRA_BEPASS_MESSAGE).orEmpty()
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AppConfig.ACTION_BEPASS_RUNTIME),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            start()
            check(latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for the Bepass SOCKS runtime"
            }
            return Triple(state, port, message)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
