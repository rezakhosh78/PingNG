package com.v2ray.ang.ui

import com.v2ray.ang.ui.compose.countryFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryFlagTest {
    @Test
    fun convertsIsoCodeToRegionalIndicatorFlag() {
        assertEquals("🇩🇪", countryFlag("de"))
    }

    @Test
    fun rejectsNonIsoValues() {
        assertNull(countryFlag("Germany"))
        assertNull(countryFlag(""))
    }
}
