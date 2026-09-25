package com.v2ray.ang.dto.entities

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var pingNgProfile: String? = "Off",
    var pingNgDesyncArgs: String? = null,
    var psiphonEnabled: Boolean = false,
    var psiphonRegion: String? = "ANY",
    /** Psiphon transport selection: auto, cdn (CDN fronting), or direct. */
    var psiphonMode: String? = "auto",
    /** Optional user-supplied CDN edge IPs used by the Psiphon fronting scan. */
    var psiphonCdnIps: String? = null,
    /** Optional SNI/server names paired with [psiphonCdnIps]. */
    var psiphonCdnSni: String? = null,
    /** Built-in Psiphon CDN edge lists to scan, comma-separated; null means all. */
    var psiphonCdnSets: String? = null,
    /** Non-visible component owned by a generated composite profile. */
    var managedBy: String? = null,
    /** Last verified egress country; kept with this profile instead of UI-only state. */
    var lastExitCountryCode: String? = null,
    /** Last verified egress IP, kept with the profile for stable display. */
    var lastExitIpAddress: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var kcpMtu: Int? = null,
    var kcpTti: Int? = null,

    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var cipherSuites: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var verifyPeerCertByName: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,
    var warpKeepAlive: Int? = null,
    var warpEndpointCandidates: String? = null,
    var warpEndpointTestEnabled: Boolean? = null,
    var warpEndpointTestMode: String? = null,
    /** FinalMask selected for Fast mode; null keeps the default fallback. */
    var warpFastFinalMask: String? = null,
    /** FinalMask selected for Medium/Slow mode; null keeps the default fallback. */
    var warpAllFinalMask: String? = null,
    var warpFinalMaskEnabled: Boolean? = null,
    var warpInnerEndpointCandidates: String? = null,
    var warpOuterEndpointCandidates: String? = null,
    /** Last endpoint pair selected by the WARP Plus endpoint tester. */
    var warpSelectedEndpoint: String? = null,
    /** Last single-hop WARP WireGuard endpoint selected by its tester. */
    var warpWireGuardSelectedEndpoint: String? = null,
    /** Proxy profile used to register new WARP accounts (WireGuard, Plus, or MASQUE); null means Auto. */
    var warpRegistrationProxyGuid: String? = null,
    /** Internal MASQUE carrier bundled for WARP registration; never publish in profile lists. */
    var warpRegistrationInternalProxy: Boolean? = null,
    /** Skip one automatic endpoint retest after a FinalMask result is applied. */
    var warpSkipEndpointTestOnce: Boolean? = null,
    /** Candidate IPv4 endpoints for the WARP MASQUE/HTTP2 core. */
    var warpMasqueEndpointCandidates: String? = null,
    /** Last MASQUE endpoint selected by the fast endpoint probe. */
    var warpMasqueSelectedEndpoint: String? = null,
    /** User-editable MASQUE registration and transport settings. */
    var warpMasqueDeviceName: String? = null,
    var warpMasquePrimaryEndpoint: String? = null,
    var warpMasqueEndpointPort: Int? = null,
    var warpMasqueEndpointMode: String? = null,
    var warpMasqueSni: String? = null,
    var warpMasqueHttp2Enabled: Boolean? = null,
    var warpMasqueSocksBind: String? = null,
    var warpMasqueSocksPort: Int? = null,
    var warpMasqueDns: String? = null,
    /** Optional complete MASQUE JSON, useful when registration is blocked. */
    var warpMasqueConfigJson: String? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    @Deprecated("Use pinnedCA256")
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
    var policyGroupTestOutbounds: Boolean? = null,
    var policyGroupFallbackTag: String? = null,
    var proxyChainProfiles: String? = null,

    var browserDialerMode: String? = null,
) {

    companion object {
        fun create(configType: EConfigType): ProfileItem =
            ProfileItem(configType = configType)
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "${AppConfig.LOOPBACK}:${AppConfig.PORT_SOCKS}"
        }
        return "${Utils.getIpv6Address(server)}:$serverPort"
    }

    /**
     * Dedicated identity for "remove duplicate configurations".
     *
     * Ignores metadata that does not affect connection:
     * - configVersion
     * - subscriptionId
     * - addedTime
     * - remarks
     * - description
     *
     * All other fields, including configType, are included in the comparison.
     *
     * Returns a copy; the caller must not modify it further.
     */
    fun duplicateIdentity(): ProfileItem =
        copy(
            configVersion = 0,
            subscriptionId = "",
            addedTime = 0L,
            remarks = "",
            description = null
        )
}
