package com.v2ray.ang.ui.server

/** The CDN edge lists exposed by the Psiphon fronting scanner. */
enum class PsiphonCdnSet(val key: String) {
    CLOUDFLARE("cloudflare"),
    FASTLY("fastly"),
    CLOUDFRONT("cloudfront"),
    AKAMAI("psiphon-akamai"),
    BUNNY("psiphon-bunny"),
    VERCEL("vercel"),
    GITHUB("github"),
    CURATED("curated-fronting"),
    LEGACY("legacy-android-overrides");

    companion object {
        fun parse(value: String?): Set<PsiphonCdnSet> {
            val keys = value.orEmpty().split(Regex("[,\\s]+"))
                .filter(String::isNotBlank).toSet()
            return entries.filter { it.key in keys }.toSet()
        }

        fun join(values: Collection<PsiphonCdnSet>): String? = entries
            .filter { it in values }
            .joinToString(",") { it.key }
            .ifBlank { null }
    }
}
