package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpProxyChoiceLabelsTest {
    @Test
    fun autoAndBundledLabelsCannotShadowUserProfiles() {
        val choices = WarpProxyChoiceLabels.makeUnique(
            listOf(
                WarpProxyChoiceLabel("bundled", "Proxy-1"),
                WarpProxyChoiceLabel("auto-named-profile", "Auto"),
                WarpProxyChoiceLabel("proxy-1-profile", "Proxy-1"),
            ),
            reservedLabels = setOf("Auto"),
        )

        assertEquals(listOf("Proxy-1", "Auto (2)", "Proxy-1 (2)"), choices.map { it.label })
        assertEquals("auto-named-profile", WarpProxyChoiceLabels.guidForLabel("Auto (2)", choices, "__AUTO__", "Auto"))
        assertEquals("proxy-1-profile", WarpProxyChoiceLabels.guidForLabel("Proxy-1 (2)", choices, "__AUTO__", "Auto"))
        assertEquals("__AUTO__", WarpProxyChoiceLabels.guidForLabel("Auto", choices, "__AUTO__", "Auto"))
    }

    @Test
    fun duplicateNamesAndGeneratedSuffixesRemainUnique() {
        val choices = WarpProxyChoiceLabels.makeUnique(
            listOf(
                WarpProxyChoiceLabel("one", "Node"),
                WarpProxyChoiceLabel("two", "Node"),
                WarpProxyChoiceLabel("three", "Node (2)"),
                WarpProxyChoiceLabel("blank", ""),
            ),
        )

        assertEquals(listOf("Node", "Node (2)", "Node (2) (2)", "blank"), choices.map { it.label })
        assertEquals(choices.size, choices.map { it.label }.toSet().size)
    }

    @Test
    fun deletedProxySelectionFallsBackToAutoAndPersistsThatChoice() {
        val choices = listOf(WarpProxyChoiceLabel("available", "Available"))

        assertEquals("__AUTO__", WarpProxyChoiceLabels.normalizeGuid("deleted", choices, "__AUTO__"))
        assertEquals("available", WarpProxyChoiceLabels.normalizeGuid("available", choices, "__AUTO__"))
    }
}
