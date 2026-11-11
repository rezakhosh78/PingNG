package com.v2ray.ang.core

/** A display label paired with the stable profile ID used by the selection. */
internal data class WarpProxyChoiceLabel(val guid: String, val label: String)

/** Keeps dropdown labels unique while keeping profile GUIDs as the selection identity. */
internal object WarpProxyChoiceLabels {
    fun makeUnique(
        choices: List<WarpProxyChoiceLabel>,
        reservedLabels: Set<String> = emptySet(),
    ): List<WarpProxyChoiceLabel> {
        val used = reservedLabels.toMutableSet()
        return choices.map { choice ->
            val baseLabel = choice.label.ifBlank { choice.guid }
            var label = baseLabel
            var suffix = 2
            while (!used.add(label)) {
                label = "$baseLabel ($suffix)"
                suffix++
            }
            choice.copy(label = label)
        }
    }

    fun normalizeGuid(guid: String, choices: List<WarpProxyChoiceLabel>, autoGuid: String): String =
        if (guid == autoGuid || choices.any { it.guid == guid }) guid else autoGuid

    fun labelForGuid(
        guid: String,
        choices: List<WarpProxyChoiceLabel>,
        autoGuid: String,
        autoLabel: String,
    ): String = if (guid == autoGuid) autoLabel else choices.firstOrNull { it.guid == guid }?.label ?: autoLabel

    fun guidForLabel(
        label: String,
        choices: List<WarpProxyChoiceLabel>,
        autoGuid: String,
        autoLabel: String,
    ): String = if (label == autoLabel) autoGuid else choices.firstOrNull { it.label == label }?.guid ?: autoGuid
}
