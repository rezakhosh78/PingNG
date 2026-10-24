package com.v2ray.ang.dto

import java.io.Serializable

/** Runtime state for the Psiphon second layer of one selected profile. */
data class PsiphonStatus(
    val guid: String,
    val state: String,
) : Serializable {
    companion object {
        const val CONNECTING = "CONNECTING"
        const val CONNECTED = "CONNECTED"
        const val FAILED = "FAILED"
        const val STOPPED = "STOPPED"
    }
}
