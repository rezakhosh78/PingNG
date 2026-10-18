package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig

/** Safely extracts registration IDs from the shared activity broadcast channel. */
internal object MasqueRegistrationMessagePolicy {
    fun registrationGuid(key: Int, content: Any?): String? {
        if (key != AppConfig.MSG_MASQUE_REGISTRATION_READY &&
            key != AppConfig.MSG_MASQUE_REGISTRATION_FAILED
        ) return null
        return (content as? String)?.takeIf(String::isNotBlank)
    }
}
