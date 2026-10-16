package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.AwgWarpConfig
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpWireGuardConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class DefaultProfileSeedBatch(
    val profiles: LinkedHashMap<String, ProfileItem>,
    val preferredSelectionGuid: String?,
)

/** Builds the missing built-in WARP entries without replacing user profiles. */
internal object DefaultProfileSeedFactory {
    private const val WARP_ENDPOINT = "162.159.192.1"
    private const val WARP_PORT = "2408"
    private const val WARP_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val WARP_PLUS_REMARK = "WARP Plus"

    fun createMissing(
        existingDefaultProfiles: List<Pair<String, ProfileItem>>,
        subscriptionId: String = AppConfig.DEFAULT_SUBSCRIPTION_ID,
        guidFactory: () -> String = { UUID.randomUUID().toString() },
    ): DefaultProfileSeedBatch {
        val visible = existingDefaultProfiles.filter { it.second.managedBy.isNullOrBlank() }
        val profiles = linkedMapOf<String, ProfileItem>()
        var preferredSelectionGuid = visible.firstOrNull {
            WarpMasqueConfig.isDescription(it.second.description)
        }?.first

        fun newGuid(): String = guidFactory().also { guid ->
            require(guid.isNotBlank() && guid !in profiles) { "Default profile GUID must be unique" }
        }

        if (visible.none { WarpMasqueConfig.isDescription(it.second.description) }) {
            val guid = newGuid()
            profiles[guid] = ProfileItem.create(EConfigType.WARP).apply {
                this.subscriptionId = subscriptionId
                remarks = "WARP MASQUE/H2"
                description = WarpMasqueConfig.DESCRIPTION
                server = WarpMasqueConfig.DEFAULT_ENDPOINT
                serverPort = WarpMasqueConfig.DEFAULT_PORT.toString()
                warpMasqueEndpointCandidates = WarpMasqueConfig.DEFAULT_ENDPOINTS
                warpMasquePrimaryEndpoint = WarpMasqueConfig.DEFAULT_ENDPOINT
                warpMasqueEndpointPort = WarpMasqueConfig.DEFAULT_PORT
                warpMasqueEndpointMode = WarpMasqueConfig.ENDPOINT_MODE_AUTO
                autoScanEndpoint = true
                warpMasqueDeviceName = WarpMasqueConfig.randomDeviceName()
                warpMasqueSni = WarpMasqueConfig.DEFAULT_SNI
                warpMasqueDns = WarpMasqueConfig.DEFAULT_DNS
                warpMasqueHttp2Enabled = true
                warpMasqueSocksBind = WarpMasqueConfig.DEFAULT_SOCKS_BIND
                warpMasqueSocksPort = WarpMasqueConfig.DEFAULT_SOCKS_PORT
                warpRegistrationProxyGuid = WarpRegistrationProxy.AUTO
            }
            preferredSelectionGuid = guid
        }

        if (visible.none { WarpWireGuardConfig.isProfile(it.second) }) {
            val guid = newGuid()
            profiles[guid] = ProfileItem.create(EConfigType.WIREGUARD).apply {
                this.subscriptionId = subscriptionId
                remarks = "WARP WireGuard"
                description = WarpWireGuardConfig.DESCRIPTION
                server = WARP_ENDPOINT
                serverPort = WARP_PORT
                publicKey = WARP_PEER_PUBLIC_KEY
                secretKey = ""
                localAddress = AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
                reserved = "0,0,0"
                mtu = 1280
                warpKeepAlive = WarpPlusConfig.DEFAULT_KEEP_ALIVE
                warpEndpointTestEnabled = true
                warpEndpointTestMode = WarpWireGuardConfig.ENDPOINT_MODE_AUTO
                autoScanEndpoint = true
                warpRegistrationProxyGuid = WarpRegistrationProxy.AUTO
            }
            if (preferredSelectionGuid == null) preferredSelectionGuid = guid
        }

        if (visible.none { AwgWarpConfig.isProfile(it.second) }) {
            val guid = newGuid()
            profiles[guid] = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
                this.subscriptionId = subscriptionId
                remarks = AwgWarpConfig.DEFAULT_REMARK
                description = AwgWarpConfig.DESCRIPTION
                server = AwgWarpConfig.DEFAULT_ENDPOINT.substringBeforeLast(':')
                serverPort = AwgWarpConfig.DEFAULT_ENDPOINT.substringAfterLast(':')
                localAddress = AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
                publicKey = WARP_PEER_PUBLIC_KEY
                secretKey = ""
                reserved = "0,0,0"
                mtu = 1280
                amneziawgConfig = AmneziaWgFmt.EMPTY_EDIT_CONFIG
                autoScanEndpoint = AwgWarpConfig.DEFAULT_AUTO_SCAN_ENDPOINT
                warpRegistrationProxyGuid = WarpRegistrationProxy.AUTO
            }
            if (preferredSelectionGuid == null) preferredSelectionGuid = guid
        }

        if (visible.none { WarpPlusConfig.isDescription(it.second.description) }) {
            val parentGuid = newGuid()
            val suffix = parentGuid.take(8)
            val outerGuid = newGuid()
            val innerGuid = newGuid()
            val outerRemark = "$WARP_PLUS_REMARK • Outer WARP • $suffix"
            val innerRemark = "$WARP_PLUS_REMARK • Inner WARP • $suffix"

            profiles[outerGuid] = ProfileItem.create(EConfigType.WIREGUARD).apply {
                this.subscriptionId = subscriptionId
                remarks = outerRemark
                managedBy = parentGuid
                server = "engage.cloudflareclient.com"
                serverPort = WARP_PORT
                publicKey = WARP_PEER_PUBLIC_KEY
                secretKey = ""
                localAddress = AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
                reserved = "0,0,0"
                mtu = 1280
                warpKeepAlive = WarpPlusConfig.DEFAULT_KEEP_ALIVE
                finalMask = WarpPlusConfig.DEFAULT_FINAL_MASK
            }
            profiles[innerGuid] = ProfileItem.create(EConfigType.WIREGUARD).apply {
                this.subscriptionId = subscriptionId
                remarks = innerRemark
                managedBy = parentGuid
                server = WARP_ENDPOINT
                serverPort = WARP_PORT
                publicKey = WARP_PEER_PUBLIC_KEY
                secretKey = ""
                localAddress = AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
                reserved = "0,0,0"
                mtu = 1280
                warpKeepAlive = WarpPlusConfig.DEFAULT_KEEP_ALIVE
            }
            profiles[parentGuid] = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
                this.subscriptionId = subscriptionId
                remarks = WARP_PLUS_REMARK
                description = WarpPlusConfig.DESCRIPTION
                proxyChainProfiles = "$innerRemark,$outerRemark"
                warpEndpointTestEnabled = true
                warpEndpointTestMode = WarpPlusConfig.ENDPOINT_MODE_AUTO
                autoScanEndpoint = true
                warpFastFinalMask = WarpPlusConfig.DEFAULT_FINAL_MASK
                warpFinalMaskEnabled = true
                warpRegistrationProxyGuid = WarpRegistrationProxy.AUTO
            }
            if (preferredSelectionGuid == null) preferredSelectionGuid = parentGuid
        }

        return DefaultProfileSeedBatch(profiles, preferredSelectionGuid)
    }
}

internal data class DefaultConfigurationSeedResult(
    val groupsChanged: Boolean,
    val serverlessSubscriptionToFetch: SubscriptionCache? = null,
)

internal fun shouldSeedDefaultProfiles(seededVersion: Int, currentVersion: Int): Boolean =
    currentVersion > seededVersion

/** Adds the built-in examples once per app version, preserving existing user data. */
internal object DefaultConfigurationSeeder {
    private const val SEEDED_VERSION_KEY = "default_warp_profiles_seeded_version"

    suspend fun seedIfNeeded(): DefaultConfigurationSeedResult = withContext(Dispatchers.IO) {
        val targetVersion = BuildConfig.VERSION_CODE
        if (!shouldSeedDefaultProfiles(
                seededVersion = MmkvManager.decodeSettingsInt(SEEDED_VERSION_KEY, 0),
                currentVersion = targetVersion,
            )
        ) {
            return@withContext DefaultConfigurationSeedResult(groupsChanged = false)
        }

        val defaultId = AppConfig.DEFAULT_SUBSCRIPTION_ID
        if (MmkvManager.decodeSubscription(defaultId) == null) {
            MmkvManager.encodeSubscription(defaultId, SubscriptionItem(remarks = "Default"))
        }

        val existingDefaultProfiles = MmkvManager.decodeServerList(defaultId)
            .distinct()
            .mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
            .filter { (_, profile) -> belongsToSubscriptionGroup(profile, defaultId) }
        val batch = DefaultProfileSeedFactory.createMissing(existingDefaultProfiles, defaultId)
        if (batch.profiles.isNotEmpty()) {
            MmkvManager.saveServerProfiles(
                profiles = batch.profiles,
                rawConfigs = emptyMap(),
                subscriptionId = defaultId,
                append = true,
            )
        }
        if (MmkvManager.getSelectServer().isNullOrBlank()) {
            val firstVisibleGuid = batch.preferredSelectionGuid
                ?: existingDefaultProfiles.firstOrNull { it.second.managedBy.isNullOrBlank() }?.first
            firstVisibleGuid?.let(MmkvManager::setSelectServer)
        }

        val serverless = ensureServerlessSubscription()
        check(MmkvManager.encodeSettings(SEEDED_VERSION_KEY, targetVersion)) {
            "Failed to persist default profile seed version"
        }

        DefaultConfigurationSeedResult(
            groupsChanged = batch.profiles.isNotEmpty() || serverless != null,
            serverlessSubscriptionToFetch = serverless,
        )
    }

    private fun ensureServerlessSubscription(): SubscriptionCache? {
        val existing = MmkvManager.decodeSubscriptions().firstOrNull {
            it.subscription.url == AppConfig.SERVERLESS_SUBSCRIPTION_URL
        }
        if (existing != null) return null

        val groupId = if (MmkvManager.decodeSubscription(AppConfig.SERVERLESS_SUBSCRIPTION_ID) == null) {
            AppConfig.SERVERLESS_SUBSCRIPTION_ID
        } else {
            Utils.getUuid()
        }
        val subscription = SubscriptionItem(
            remarks = AppConfig.SERVERLESS_SUBSCRIPTION_REMARKS,
            url = AppConfig.SERVERLESS_SUBSCRIPTION_URL,
            enabled = true,
            autoUpdate = false,
        )
        MmkvManager.encodeSubscription(groupId, subscription)
        return SubscriptionCache(groupId, subscription)
    }

    fun updateServerless(subscription: SubscriptionCache) {
        try {
            val result = AngConfigManager.updateConfigViaSub(subscription)
            LogUtil.i(
                AppConfig.TAG,
                "Default ServerLess seed completed: ${result.successCount} profiles, ${result.failureCount} failures",
            )
        } catch (error: Throwable) {
            LogUtil.e(AppConfig.TAG, "Default ServerLess subscription update failed", error)
        }
    }
}
