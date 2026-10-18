package io.github.sayach.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native pages take their palette from the DSH alias tokens the page reports. */
class DshNativeThemeTest {
    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255) = intArrayOf(r, g, b, a)

    /** DSH default light theme (dsh-web-frontend body). */
    private val light = mapOf(
        "bg-base" to rgba(255, 255, 255), "bg-layer-1" to rgba(255, 255, 255), "bg-layer-2" to rgba(255, 255, 255),
        "bg-module-platform" to rgba(245, 246, 247), "label-primary" to rgba(15, 17, 21),
        "label-secondary" to rgba(97, 102, 107), "border-l2" to rgba(0, 0, 0, 26),
        "interactive-bg-hover" to rgba(38, 49, 72, 15), "button-primary-fill" to rgba(15, 17, 21),
        "label-primary-foreground" to rgba(255, 255, 255), "state-error-primary" to rgba(236, 19, 19),
        "state-success-primary" to rgba(34, 197, 94),
    )

    /** DSH default dark theme (body[data-ds-dark-theme]). */
    private val dark = light + mapOf(
        "bg-base" to rgba(21, 21, 23), "bg-layer-1" to rgba(35, 35, 36), "bg-layer-2" to rgba(44, 44, 46),
        "bg-module-platform" to rgba(53, 54, 56), "label-primary" to rgba(249, 250, 251),
        "label-secondary" to rgba(207, 211, 214), "border-l2" to rgba(255, 255, 255, 31),
        "interactive-bg-hover" to rgba(255, 255, 255, 20), "button-primary-fill" to rgba(249, 250, 251),
        "label-primary-foreground" to rgba(15, 17, 21), "state-error-primary" to rgba(242, 90, 90),
    )

    @Test fun lightThemeSeparatesCardsWithThePlatformTone() {
        val palette = DshNativeTheme.fromTokens(light, dark = false)!!
        assertEquals(0xFFF5F6F7.toInt(), palette.colors["app_background"])
        assertEquals(0xFFFFFFFF.toInt(), palette.colors["app_surface"])
        assertEquals(0xFF0F1115.toInt(), palette.colors["app_accent"])
        assertEquals(0xFFFFFFFF.toInt(), palette.colors["app_on_accent"])
        assertEquals(0x1A000000, palette.colors["app_border"])
    }

    @Test fun darkThemeUsesTheBaseAndLayeredSurface() {
        val palette = DshNativeTheme.fromTokens(dark, dark = true)!!
        assertTrue(palette.dark)
        assertEquals(0xFF151517.toInt(), palette.colors["app_background"])
        assertEquals(0xFF232324.toInt(), palette.colors["app_surface"])
        assertEquals(0xFFF9FAFB.toInt(), palette.colors["app_accent"])
        assertEquals(0xFF0F1115.toInt(), palette.colors["app_on_accent"])
    }

    @Test fun everyAppColorIsProvidedSoNoNativeViewMixesThemes() {
        val palette = DshNativeTheme.fromTokens(light, dark = false)!!
        val expected = setOf(
            "app_background", "app_surface", "app_surface_translucent", "app_setup_scrim", "app_surface_tinted",
            "app_foreground", "app_secondary", "app_border", "app_accent", "app_on_accent", "app_error", "app_success",
        )
        assertEquals(expected, palette.colors.keys)
    }

    @Test fun incompleteOrUnreadableThemesAreIgnored() {
        assertNull(DshNativeTheme.fromTokens(light - "label-primary", dark = false))
        assertNull(DshNativeTheme.fromTokens(light + ("label-primary" to rgba(250, 250, 250)), dark = false))
        assertNull(DshNativeTheme.fromTokens(light + ("bg-base" to rgba(300, 0, 0)), dark = false))
        assertNull(DshNativeTheme.fromTokens(light + ("label-primary-foreground" to rgba(20, 20, 20)), dark = false))
    }

    @Test fun translucentTokensAreCompositedOverTheirSurface() {
        assertEquals(0xFF7F7F7F.toInt(), DshNativeTheme.over(0x80000000.toInt(), 0xFFFFFFFF.toInt()))
    }
}
