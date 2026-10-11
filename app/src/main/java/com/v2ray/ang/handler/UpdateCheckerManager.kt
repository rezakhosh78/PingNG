package com.v2ray.ang.handler

import android.os.Build
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        fun numericParts(version: String): List<Int> {
            val match = Regex("\\d+(?:\\.\\d+)*").find(version) ?: return emptyList()
            return match.value.split(".").map { it.toIntOrNull() ?: 0 }
        }

        val v1 = numericParts(version1)
        val v2 = numericParts(version2)

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrElse(i) { 0 }
            val num2 = v2.getOrElse(i) { 0 }
            if (num1 != num2) return num1 - num2
        }
        val pi1 = Regex("(?i)\\bpi[-_ ]?(\\d+)").find(version1)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val pi2 = Regex("(?i)\\bpi[-_ ]?(\\d+)").find(version2)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return pi1.compareTo(pi2)
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true)
        val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val abiTokens = when (abi.lowercase()) {
            "arm64-v8a" -> listOf("arm64-v8a", "arm64", "aarch64")
            "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "armeabi")
            "x86_64" -> listOf("x86_64", "x8664")
            "x86" -> listOf("x86")
            else -> listOf(abi)
        }
        fun isFlavorMatch(asset: GitHubRelease.Asset): Boolean =
            asset.name.contains("fdroid", ignoreCase = true) == fDroid
        fun isAbiMatch(asset: GitHubRelease.Asset): Boolean =
            abiTokens.any { token -> asset.name.contains(token, ignoreCase = true) }
        fun isUniversal(asset: GitHubRelease.Asset): Boolean =
            listOf("universal", "all", "noarch", "release.apk").any {
                asset.name.contains(it, ignoreCase = true)
            }

        val asset = apkAssets.firstOrNull { isFlavorMatch(it) && isAbiMatch(it) }
            ?: apkAssets.firstOrNull { isFlavorMatch(it) && isUniversal(it) }
            ?: apkAssets.firstOrNull { isAbiMatch(it) }
            ?: apkAssets.firstOrNull { isUniversal(it) }
            ?: apkAssets.firstOrNull { isFlavorMatch(it) }
            ?: apkAssets.firstOrNull()

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }

    /** Downloads the selected release APK into the app-private cache. */
    suspend fun downloadApk(context: Context, downloadUrl: String, version: String): File =
        withContext(Dispatchers.IO) {
            val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(context.cacheDir, "PingNG-$safeVersion.apk")
            try {
                val request = UrlContentRequest(
                    url = downloadUrl,
                    timeout = 60000,
                    userAgent = "PingNG/${BuildConfig.VERSION_NAME}",
                )
                var downloaded = HttpUtil.downloadToFile(request, target)
                if (!downloaded) {
                    downloaded = HttpUtil.downloadToFile(
                        request.copy(
                            httpPort = SettingsManager.getHttpPort(),
                            proxyUsername = SettingsManager.getSocksUsername(),
                            proxyPassword = SettingsManager.getSocksPassword(),
                        ),
                        target,
                    )
                }
                if (!downloaded || !target.isFile || target.length() == 0L) {
                    throw IllegalStateException("Downloaded update is empty")
                }
                target
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
}
