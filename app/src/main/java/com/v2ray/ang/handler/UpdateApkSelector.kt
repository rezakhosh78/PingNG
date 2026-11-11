package com.v2ray.ang.handler

import com.v2ray.ang.dto.GitHubRelease

/** Selects an installable release APK for one of the device's supported ABIs. */
internal fun selectCompatibleUpdateApk(
    assets: List<GitHubRelease.Asset>,
    supportedAbis: List<String>,
    isFdroid: Boolean,
): GitHubRelease.Asset? {
    val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val hasFlavorMarkers = apkAssets.any { it.name.contains("fdroid", ignoreCase = true) }
    val flavorAssets = if (hasFlavorMarkers) {
        apkAssets.filter { it.name.contains("fdroid", ignoreCase = true) == isFdroid }
    } else {
        // Some release workflows omit the flavor suffix from APK filenames.
        apkAssets
    }

    fun isAbiMatch(asset: GitHubRelease.Asset, abi: String): Boolean {
        val name = asset.name.lowercase()
        return when (abi.lowercase()) {
            "arm64-v8a" -> Regex("(^|[^a-z0-9])(?:arm64-v8a|arm64|aarch64)([^a-z0-9]|$)")
                .containsMatchIn(name)
            "armeabi-v7a" -> Regex("(^|[^a-z0-9])(?:armeabi-v7a|armv7|armeabi)([^a-z0-9]|$)")
                .containsMatchIn(name)
            "x86_64" -> Regex("(^|[^a-z0-9])(?:x86_64|x8664|amd64)([^a-z0-9]|$)")
                .containsMatchIn(name)
            "x86" -> Regex("(^|[^a-z0-9])x86([^a-z0-9]|$)").containsMatchIn(name) &&
                !Regex("(^|[^a-z0-9])x86_64([^a-z0-9]|$)").containsMatchIn(name)
            else -> Regex("(^|[^a-z0-9])${Regex.escape(abi.lowercase())}([^a-z0-9]|$)")
                .containsMatchIn(name)
        }
    }

    val knownAbiToken = Regex(
        "(^|[^a-z0-9])(?:arm64-v8a|arm64|aarch64|armeabi-v7a|armv7|armeabi|x86_64|x8664|amd64|x86)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE,
    )
    val universalToken = Regex("(^|[^a-z0-9])(?:universal|all|noarch)([^a-z0-9]|$)", RegexOption.IGNORE_CASE)
    fun isUniversal(asset: GitHubRelease.Asset): Boolean {
        val name = asset.name.lowercase()
        val hasAbiMarker = knownAbiToken.containsMatchIn(name)
        val explicitUniversal = universalToken.containsMatchIn(name) && !hasAbiMarker
        return explicitUniversal
    }

    return supportedAbis.firstNotNullOfOrNull { abi ->
        flavorAssets.firstOrNull { isAbiMatch(it, abi) }
    } ?: flavorAssets.firstOrNull(::isUniversal)
}

/** Returns false only when a release explicitly declares a different app package. */
internal fun isReleaseCompatibleWithApplication(
    releaseNotes: String,
    applicationId: String,
): Boolean {
    val declaredApplicationId = Regex(
        """(?i)Application ID:\s*`?([A-Za-z0-9_.]+)`?"""
    ).find(releaseNotes)?.groupValues?.getOrNull(1)
    return declaredApplicationId == null || declaredApplicationId == applicationId
}
