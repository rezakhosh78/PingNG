package com.v2ray.ang.core

/** Keeps MASQUE key registration from restarting the proxy-only service that owns startup. */
internal object WarpMasqueRegistrationExecutionPolicy {
    enum class Host { VPN, ROOT, PROXY_ONLY, OTHER }
    enum class Execution { TEMPORARY_PROXY_ONLY_SERVICE, CURRENT_SERVICE }

    fun executionFor(host: Host): Execution =
        if (host == Host.PROXY_ONLY) Execution.CURRENT_SERVICE
        else Execution.TEMPORARY_PROXY_ONLY_SERVICE
}
