package com.v2ray.ang.ui.compose

/** Converts an ISO 3166-1 alpha-2 country code into its regional-indicator flag. */
fun countryFlag(countryCode: String?): String? {
    val code = countryCode?.trim()?.uppercase() ?: return null
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
    return code.map { char -> String(Character.toChars(0x1F1E6 + char.code - 'A'.code)) }
        .joinToString("")
}

/** A fixed country selected for this profile, excluding Psiphon's automatic mode. */
fun configuredCountryCode(psiphonEnabled: Boolean, psiphonRegion: String?): String? {
    if (!psiphonEnabled) return null
    val code = psiphonRegion?.trim()?.uppercase() ?: return null
    return code.takeIf { it != "ANY" && it.length == 2 && it.all { char -> char in 'A'..'Z' } }
}
