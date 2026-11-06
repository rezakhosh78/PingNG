package com.v2ray.ang.dto

import java.io.Serializable

/** Locale-neutral result sent from the daemon for presentation by the UI process. */
data class ConnectionTestResult(
    val delayMillis: Long,
    val errorMessage: String = "",
    val country: String? = null,
    val countryCode: String? = null,
    val ipAddress: String? = null,
    /** Stable profile key used to update the matching server row for standalone VPN probes. */
    val profileGuid: String? = null,
) : Serializable
