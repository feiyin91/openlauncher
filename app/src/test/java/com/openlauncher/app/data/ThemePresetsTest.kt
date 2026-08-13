package com.openlauncher.app.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemePresetsTest {

    @Test
    fun `night mode resolves the theme's dark accent, background, and ink`() {
        val resolved = resolveDashboardTheme("instrument", isDayMode = false)
        assertEquals(Color(0xFFFFA23DL), resolved?.accent)
        assertEquals(Color(0xFF14100AL), resolved?.background)
        assertEquals(Color(0xFFF3ECDDL), resolved?.ink)
    }

    @Test
    fun `day mode resolves the theme's light accent and background, with no ink override`() {
        val resolved = resolveDashboardTheme("instrument", isDayMode = true)
        assertEquals(Color(0xFF1F2A44L), resolved?.accent)
        assertEquals(Color(0xFFF0E6D2L), resolved?.background)
        assertNull(resolved?.ink)
    }

    @Test
    fun `every preset resolves in both day and night mode`() {
        DASHBOARD_THEMES.forEach { theme ->
            assertEquals(theme.id, resolveDashboardTheme(theme.id, isDayMode = false)?.let { theme.id })
            assertEquals(theme.id, resolveDashboardTheme(theme.id, isDayMode = true)?.let { theme.id })
        }
    }

    @Test
    fun `an unrecognized theme id (including 'custom') resolves to null`() {
        assertNull(resolveDashboardTheme("custom", isDayMode = false))
        assertNull(resolveDashboardTheme("not-a-real-theme", isDayMode = true))
    }
}
