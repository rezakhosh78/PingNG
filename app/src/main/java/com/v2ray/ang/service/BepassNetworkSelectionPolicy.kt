package com.v2ray.ang.service

/** Small platform-independent input used to choose Bepass's physical uplink. */
internal data class BepassNetworkCandidate<T>(
    val network: T,
    val hasInternet: Boolean,
    val isNotVpn: Boolean,
    val isValidated: Boolean,
    val transportPriority: Int,
)

internal object BepassNetworkSelectionPolicy {
    fun <T> best(
        candidates: Iterable<BepassNetworkCandidate<T>>,
        excludedNetwork: T? = null,
    ): T? = candidates.asSequence()
        .filter { it.hasInternet && it.isNotVpn && it.network != excludedNetwork }
        .maxByOrNull { candidate ->
            (if (candidate.isValidated) 100 else 0) + candidate.transportPriority
        }
        ?.network
}
