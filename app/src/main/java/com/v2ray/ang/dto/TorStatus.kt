package com.v2ray.ang.dto

import java.io.Serializable

/** Runtime status for Tor on the profile currently being connected. */
data class TorStatus(
    val guid: String,
    val state: String,
) : Serializable {
    companion object {
        const val CONNECTING = "CONNECTING"
        const val CONNECTED = "CONNECTED"
        const val STOPPED = "STOPPED"
    }
}
