package com.v2ray.ang.core

data class SoarResolverScanSummary(
    val discovery: Boolean,
    val checked: Int,
    val total: Int,
    val verified: Int,
    val time: String,
)

data class SoarLogPresentation(
    val scans: List<SoarResolverScanSummary>,
    val details: String,
)

/** Converts machine-readable resolver progress into compact scan summaries for the profile log. */
object SoarLogFormatter {
    private val progressPattern = Regex(
        "SOAR_DNS_SCAN\\s+(?:(discovery|manual)\\s+)?(\\d+)\\s+(\\d+)\\s+(\\d+)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(log: String): SoarLogPresentation {
        val scans = mutableListOf<SoarResolverScanSummary>()
        val detailLines = mutableListOf<String>()

        for (line in log.lineSequence()) {
            val match = progressPattern.find(line)
            if (match == null) {
                if (line.isNotEmpty()) detailLines += line
                continue
            }

            val checked = match.groupValues[2].toIntOrNull()
            val total = match.groupValues[3].toIntOrNull()
            val verified = match.groupValues[4].toIntOrNull()
            if (checked == null || total == null || verified == null ||
                total <= 0 || checked !in 0..total || verified !in 0..checked
            ) {
                detailLines += line
                continue
            }

            val mode = match.groupValues[1].lowercase()
            val discovery = when (mode) {
                "discovery" -> true
                "manual" -> false
                // Before mode tags were added, only discovery progress was written to the log.
                else -> true
            }
            val summary = SoarResolverScanSummary(
                discovery = discovery,
                checked = checked,
                total = total,
                verified = verified,
                time = line.substring(0, match.range.first).trim(),
            )
            val previous = scans.lastOrNull()
            if (previous == null || previous.discovery != summary.discovery ||
                previous.total != summary.total || summary.checked < previous.checked ||
                summary.verified < previous.verified
            ) {
                scans += summary
            } else {
                scans[scans.lastIndex] = summary
            }
        }

        return SoarLogPresentation(scans, detailLines.joinToString("\n"))
    }
}
