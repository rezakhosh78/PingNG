package com.v2ray.ang.handler

import com.v2ray.ang.dto.GitHubRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UpdateApkSelectorTest {
    @Test
    fun prefersTheFirstSupportedAbiOverReleaseAssetOrder() {
        val arm32 = asset("PingNG_v2.3.12_armeabi-v7a.apk")
        val arm64 = asset("PingNG_v2.3.12_arm64-v8a.apk")

        val selected = selectCompatibleUpdateApk(
            assets = listOf(arm32, arm64),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            isFdroid = false,
        )

        assertEquals(arm64, selected)
    }

    @Test
    fun doesNotTreatX8664ApkAsCompatibleWithX86Device() {
        val x8664 = asset("PingNG_v2.3.12_x86_64.apk")
        val universal = asset("PingNG_v2.3.12_universal.apk")

        val selected = selectCompatibleUpdateApk(
            assets = listOf(x8664, universal),
            supportedAbis = listOf("x86"),
            isFdroid = false,
        )

        assertEquals(universal, selected)
    }

    @Test
    fun returnsNoApkInsteadOfDownloadingAnUnsupportedArchitecture() {
        val arm64 = asset("PingNG_v2.3.12_arm64-v8a.apk")

        val selected = selectCompatibleUpdateApk(
            assets = listOf(arm64),
            supportedAbis = listOf("x86_64", "x86"),
            isFdroid = false,
        )

        assertNull(selected)
    }

    @Test
    fun doesNotAssumeAnUnmarkedReleaseApkSupportsEveryAbi() {
        val generic = asset("PingNG_release.apk")

        val selected = selectCompatibleUpdateApk(
            assets = listOf(generic),
            supportedAbis = listOf("arm64-v8a"),
            isFdroid = false,
        )

        assertNull(selected)
    }

    @Test
    fun selectsTheMatchingBuildFlavorWhenReleaseNamesIncludeFlavor() {
        val regular = asset("PingNG_v2.3.12_arm64-v8a.apk")
        val fdroid = asset("PingNG_v2.3.12_fdroid_arm64-v8a.apk")

        val selected = selectCompatibleUpdateApk(
            assets = listOf(regular, fdroid),
            supportedAbis = listOf("arm64-v8a"),
            isFdroid = true,
        )

        assertEquals(fdroid, selected)
    }

    @Test
    fun rejectsReleaseMetadataForAnotherBuildFlavor() {
        val notes = "F-Droid build\nApplication ID: `com.pingng.android.fdroid`"

        assertEquals(
            true,
            isReleaseCompatibleWithApplication(notes, "com.pingng.android.fdroid"),
        )
        assertEquals(
            false,
            isReleaseCompatibleWithApplication(notes, "com.pingng.android"),
        )
    }

    private fun asset(name: String) = GitHubRelease.Asset(
        name = name,
        browserDownloadUrl = "https://example.invalid/$name",
    )
}
