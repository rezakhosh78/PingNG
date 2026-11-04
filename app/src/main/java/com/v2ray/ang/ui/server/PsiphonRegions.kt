package com.v2ray.ang.ui.server

import com.v2ray.ang.ui.compose.countryFlag

object PsiphonRegions {
    private val regions = listOf(
        "ANY" to "Best Connection", "US" to "United States", "CA" to "Canada",
        "GB" to "United Kingdom", "DE" to "Germany", "FR" to "France",
        "NL" to "Netherlands", "SE" to "Sweden", "CH" to "Switzerland",
        "AT" to "Austria", "BE" to "Belgium", "ES" to "Spain",
        "IT" to "Italy", "PL" to "Poland", "RO" to "Romania",
        "UA" to "Ukraine", "TR" to "Türkiye", "AE" to "United Arab Emirates",
        "IL" to "Israel", "IN" to "India", "JP" to "Japan",
        "KR" to "South Korea", "SG" to "Singapore", "HK" to "Hong Kong",
        "AU" to "Australia", "BR" to "Brazil", "MX" to "Mexico",
        "ZA" to "South Africa"
    )

    fun displayOptions(): List<String> = regions.map { displayName(it.first) }

    fun displayName(code: String): String = regions.firstOrNull {
        it.first.equals(code, ignoreCase = true)
    }?.let { region ->
        if (region.first == "ANY") return region.second
        val flag = countryFlag(region.first)
        listOfNotNull(flag, "${region.first} — ${region.second}").joinToString(" ")
    } ?: "Best Connection"

    fun codeOf(display: String): String = regions.firstOrNull { region ->
        display.contains("${region.first} —")
    }?.first ?: if (display.trim().equals("Best Connection", ignoreCase = true)) {
        "ANY"
    } else {
        display.substringBefore('—').trim().uppercase()
    }
}
