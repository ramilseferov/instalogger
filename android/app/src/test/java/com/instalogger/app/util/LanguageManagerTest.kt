package com.instalogger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageManagerTest {

    @Test
    fun normalizeLanguageCode_handlesValidAndInvalidInputs() {
        assertEquals("az", LanguageManager.normalizeLanguageCode("az"))
        assertEquals("az", LanguageManager.normalizeLanguageCode("  AZ "))
        assertEquals("en", LanguageManager.normalizeLanguageCode("EN"))
        assertEquals("ru", LanguageManager.normalizeLanguageCode("ru"))
        assertNull(LanguageManager.normalizeLanguageCode("fr"))
        assertNull(LanguageManager.normalizeLanguageCode(""))
        assertNull(LanguageManager.normalizeLanguageCode(null))
    }

    @Test
    fun getLocale_mapsSupportedLanguagesConsistently() {
        val az = LanguageManager.getLocale("az")
        val en = LanguageManager.getLocale("en")
        val ru = LanguageManager.getLocale("ru")
        val fallback = LanguageManager.getLocale("unknown")

        assertEquals("az", az.language)
        assertEquals("AZ", az.country)
        assertEquals("en", en.language)
        assertEquals("US", en.country)
        assertEquals("ru", ru.language)
        assertEquals("RU", ru.country)
        assertEquals("en", fallback.language)
    }
}
