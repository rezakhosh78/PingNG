package com.v2ray.ang.core

/** Pure package-filter policy shared by VPN-backed tunnel profiles. */
internal object PerAppVpnRoutingPolicy {
    data class Rules(
        val allowedPackages: Set<String> = emptySet(),
        val disallowedPackages: Set<String> = emptySet(),
    )

    /**
     * Builds Android VpnService package filters.
     *
     * Direct AWG protects its own transport sockets and can include PingNG. Profiles
     * that route through an Xray/HEV second layer keep PingNG outside the TUN to avoid loops.
     */
    fun create(
        enabled: Boolean,
        bypassMode: Boolean,
        selectedPackages: Set<String>,
        servicePackage: String,
        excludeServicePackage: Boolean,
        includeServicePackageInProxyAllowlist: Boolean = false,
    ): Rules {
        val selected = selectedPackages.filterTo(linkedSetOf<String>()) { it.isNotBlank() }
        if (!enabled || selected.isEmpty()) {
            return if (excludeServicePackage) {
                Rules(disallowedPackages = setOf(servicePackage))
            } else {
                Rules()
            }
        }

        if (bypassMode) {
            val disallowed = selected.toMutableSet()
            if (excludeServicePackage) disallowed += servicePackage
            return Rules(disallowedPackages = disallowed)
        }

        val allowed = when {
            excludeServicePackage -> selected - servicePackage
            includeServicePackageInProxyAllowlist && !bypassMode -> selected + servicePackage
            else -> selected
        }
        // Android interprets an empty allowlist as no allowlist (all apps). Keep the
        // service excluded if the only selected app was PingNG itself.
        if (excludeServicePackage && allowed.isEmpty()) {
            return Rules(disallowedPackages = setOf(servicePackage))
        }
        return Rules(allowedPackages = allowed)
    }
}
