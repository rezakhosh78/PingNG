package com.v2ray.ang.util

import android.util.Log
import com.rkh.vpn.data.MspPrivacy
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.MspManager
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale

object LogUtil {

    private const val DEFAULT_LEVEL = "warning"
    private const val CACHE_UNSET = Int.MIN_VALUE

    @Volatile
    private var cachedMinPriority: Int = CACHE_UNSET

    private fun parsePriority(level: String?): Int {
        return when ((level ?: DEFAULT_LEVEL).lowercase(Locale.US)) {
            "verbose" -> Log.VERBOSE
            "debug" -> Log.DEBUG
            "info" -> Log.INFO
            "warn", "warning" -> Log.WARN
            "error" -> Log.ERROR
            "none", "off" -> Int.MAX_VALUE
            else -> Log.WARN
        }
    }

    @Suppress("unused")
    fun refreshLogLevel() {
        cachedMinPriority = parsePriority(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL))
    }

    private fun minPriority(): Int {
        val cached = cachedMinPriority
        if (cached != CACHE_UNSET) {
            return cached
        }

        return synchronized(this) {
            val current = cachedMinPriority
            if (current != CACHE_UNSET) {
                current
            } else {
                parsePriority(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL)).also {
                    cachedMinPriority = it
                }
            }
        }
    }

    private fun isEnabled(priority: Int): Boolean {
        return priority >= minPriority()
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val mspPrivacyActive = runCatching {
            MspManager.shouldRedactLogs(AngApplication.application.applicationContext)
        }.getOrDefault(false)
        val safeMessage = if (mspPrivacyActive) MspPrivacy.redact(message) else message
        val safeThrowable = if (mspPrivacyActive) MspPrivacy.redactThrowable(throwable) else throwable
        if (priority >= Log.INFO) {
            // Keep a cross-process lifecycle/error trail even when Android logcat is cleared.
            runCatching { PingNgDiagnostics.recordLog(priority, tag, safeMessage, safeThrowable) }
        }
        if (!isEnabled(priority)) return

        when {
            safeThrowable == null -> Log.println(priority, tag, safeMessage)
            priority >= Log.ERROR -> Log.e(tag, safeMessage, safeThrowable)
            priority == Log.WARN -> Log.w(tag, safeMessage, safeThrowable)
            priority == Log.INFO -> Log.i(tag, safeMessage, safeThrowable)
            priority == Log.DEBUG -> Log.d(tag, safeMessage, safeThrowable)
            else -> Log.v(tag, safeMessage, safeThrowable)
        }
    }

    fun d(tag: String = AppConfig.TAG, message: String) = log(Log.DEBUG, tag, message)
    fun i(tag: String = AppConfig.TAG, message: String) = log(Log.INFO, tag, message)
    fun w(tag: String = AppConfig.TAG, message: String) = log(Log.WARN, tag, message)
    fun e(tag: String = AppConfig.TAG, message: String) = log(Log.ERROR, tag, message)

    fun d(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.DEBUG, tag, message, throwable)
    fun i(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.INFO, tag, message, throwable)
    fun w(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.ERROR, tag, message, throwable)
}
