package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** Configuration understood by the bundled Shir-o-Khorshid Psiphon core. */
object PsiphonCdnFrontingConfig {
    private const val SETS_ASSET = "pingng_psiphon_cdn_sets.json"
    private val ipv4Candidate = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?:/(?:[0-9]|[12][0-9]|3[0-2]))?")
    private val hostCandidate = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", RegexOption.IGNORE_CASE)

    private fun tokens(raw: String): List<String> = raw.split(',', ';', ' ', '\t', '\n', '\r')
        .map(String::trim).filter(String::isNotBlank).distinct()

    private fun validIp(value: String): Boolean = ipv4Candidate.matches(value) &&
        value.substringBefore('/').split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }

    fun apply(root: JsonObject, context: Context, mode: String, customIps: String, customSni: String, selectedSets: String) {
        if (mode.equals("direct", ignoreCase = true)) return
        val requestedSets = tokens(selectedSets).toSet()
        var appliedSets = 0
        val ips = linkedSetOf<String>()
        val names = linkedSetOf<String>()
        tokens(customIps).filterTo(ips, ::validIp)
        tokens(customSni).map { it.lowercase(Locale.ROOT).removeSuffix(".") }
            .filterTo(names) { hostCandidate.matches(it) && it.contains('.') }

        // This core accepts exactly one user spec and a boolean for all built-in
        // sets. A named-set JSON option does not exist. Populate the user spec
        // from the same candidate lists shipped with its source when selected.
        if (requestedSets.isNotEmpty()) {
            val corpus = JsonParser.parseReader(context.assets.open(SETS_ASSET).bufferedReader()).asJsonObject
            requestedSets.forEach { key ->
                val set = corpus.getAsJsonObject(key) ?: return@forEach
                appliedSets++
                set.getAsJsonArray("ips")?.forEach { ips += it.asString }
                set.getAsJsonArray("sni")?.forEach { names += it.asString }
            }
        }
        if (ips.isNotEmpty()) {
            root.add("FrontedMeekCDNScanSpec", JsonObject().apply {
                add("IPCandidates", JsonArray().also { out -> ips.forEach(out::add) })
                if (names.isNotEmpty()) add("SNIServerNames", JsonArray().also { out -> names.forEach(out::add) })
            })
        }
        // Selected lists are candidates, not a guarantee that an edge still
        // works on the current network. Always retain the core's built-in
        // fronting candidates as a fallback.
        if (mode.equals("cdn", ignoreCase = true) || ips.isNotEmpty() || names.isNotEmpty()) {
            root.addProperty("FrontedMeekCDNScanUseBuiltInSpec", true)
            PingNgDiagnostics.record(
                "Psiphon CDN scan configured: candidates=${ips.size}, names=${names.size}, selectedSets=$appliedSets, builtIn=true",
            )
        }
    }
}
