package com.v2ray.ang.ui.main

/** Rejects delayed service events that belong to an older real-ping request. */
internal fun isCurrentPingRequest(activeRequestId: String?, eventRequestId: String): Boolean =
    eventRequestId.isNotBlank() && activeRequestId == eventRequestId
