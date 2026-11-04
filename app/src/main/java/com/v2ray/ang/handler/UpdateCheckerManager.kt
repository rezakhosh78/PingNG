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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            val downloadAsset = getDownloadAsset(latestRelease, Build.SUPPORTED_ABIS.toList())
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadAsset.browserDownloadUrl,
                downloadSizeBytes = downloadAsset.size.takeIf { it > 0L },
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

    private fun getDownloadAsset(
        release: GitHubRelease,
        supportedAbis: List<String>,
    ): GitHubRelease.Asset {
        if (!isReleaseCompatibleWithApplication(release.body.orEmpty(), BuildConfig.APPLICATION_ID)) {
            throw IllegalStateException("The latest release targets a different app variant")
        }
        val asset = selectCompatibleUpdateApk(
            assets = release.assets,
            supportedAbis = supportedAbis,
            isFdroid = BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true),
        )

        return asset ?: throw IllegalStateException("No APK matches the supported device ABIs")
    }

    /** Downloads the selected release APK into the app-private cache. */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        version: String,
        expectedSizeBytes: Long? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            val downloadJob: Job? = currentCoroutineContext()[Job]
            val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(context.cacheDir, "PingNG-$safeVersion.apk")
            target.delete()
            var lastReportedAtNanos = 0L
            val progress: (Long, Long) -> Unit = { downloadedBytes, totalBytes ->
                downloadJob?.ensureActive()
                val progressTotalBytes = totalBytes.takeIf { it > 0L }
                    ?: expectedSizeBytes?.takeIf { it > 0L }
                    ?: totalBytes
                val now = System.nanoTime()
                if (downloadedBytes == 0L || downloadedBytes == progressTotalBytes ||
                    now - lastReportedAtNanos >= 100_000_000L
                ) {
                    onProgress(downloadedBytes, progressTotalBytes)
                    lastReportedAtNanos = now
                }
            }
            try {
                val request = UrlContentRequest(
                    url = downloadUrl,
                    timeout = 60000,
                    userAgent = "PingNG/${BuildConfig.VERSION_NAME}",
                )
                var downloaded = HttpUtil.downloadToFileCancellable(request, target, progress)
                if (!downloaded) {
                    downloaded = HttpUtil.downloadToFileCancellable(
                        request.copy(
                            httpPort = SettingsManager.getHttpPort(),
                            proxyUsername = SettingsManager.getSocksUsername(),
                            proxyPassword = SettingsManager.getSocksPassword(),
                        ),
                        target,
                        progress,
                    )
                }
                if (!downloaded || !target.isFile || target.length() == 0L) {
                    throw IllegalStateException("Downloaded update is empty")
                }
                target
            } catch (error: CancellationException) {
                target.delete()
                throw error
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
}
