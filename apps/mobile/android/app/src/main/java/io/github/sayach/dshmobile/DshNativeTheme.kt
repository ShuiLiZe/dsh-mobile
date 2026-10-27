package io.github.sayach.dshmobile

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONObject

/**
 * Native pages (device list, pairing, settings, connection progress) follow the
 * theme of the connected DSH instead of shipping their own palette.
 *
 * The page reports a small set of resolved DSH alias tokens (`--dsw-alias-*`, read
 * from `document.body`, where DSH defines them) after every theme change. A palette
 * is cached per light/dark mode so a later cold start, before any page is loaded,
 * still uses the last DSH theme for the current system mode. Without a cache the
 * resource colors (DSH default light/dark) are used.
 */
internal data class DshThemePalette(
    val dark: Boolean,
    /** App color resource name -> ARGB. */
    val colors: Map<String, Int>,
)

internal object DshNativeTheme {
    /** DSH tokens the page sends; every one must be present for a palette to be accepted. */
    val TOKENS = listOf(
        "bg-base", "bg-layer-1", "bg-layer-2", "bg-module-platform",
        "label-primary", "label-secondary", "border-l2", "interactive-bg-hover",
        "button-primary-fill", "label-primary-foreground",
        "state-error-primary", "state-success-primary",
    )

    private const val PREFERENCE_PREFIX = "dsh_native_theme_v1_"
    private const val MIN_TEXT_CONTRAST = 3.0
    private const val MAX_TOKEN_COUNT = 32

    /**
     * Builds the native palette from reported tokens.
     * @param tokens token name -> [r, g, b, a] (0..255 each)
     * @return null when a token is missing, malformed, or the text would be unreadable.
     */
    fun fromTokens(tokens: Map<String, IntArray>, dark: Boolean): DshThemePalette? {
        if (tokens.size > MAX_TOKEN_COUNT) return null
        val argb = HashMap<String, Int>()
        for (name in TOKENS) {
            val value = tokens[name] ?: return null
            if (value.size != 4 || value.any { it !in 0..255 }) return null
            argb[name] = (value[3] shl 24) or (value[0] shl 16) or (value[1] shl 8) or value[2]
        }
        // Opaque bases first: layered surfaces may be translucent over the page base.
        val base = opaque(argb.getValue("bg-base"), if (dark) 0xFF000000.toInt() else -1)
        val layer1 = over(argb.getValue("bg-layer-1"), base)
        val layer2 = over(argb.getValue("bg-layer-2"), base)
        val platform = over(argb.getValue("bg-module-platform"), base)
        // DSH light keeps base and layers identical (white); separate cards from the
        // page with the platform tone, exactly like the DSH sidebar/content split.
        val background = if (distance(base, layer2) < 6) platform else base
        val surface = if (background == base && distance(base, layer1) >= 6) layer1 else layer2
        val tinted = over(argb.getValue("interactive-bg-hover"), surface)
        val foreground = over(argb.getValue("label-primary"), surface)
        val secondary = over(argb.getValue("label-secondary"), surface)
        val accent = over(argb.getValue("button-primary-fill"), surface)
        val onAccent = over(argb.getValue("label-primary-foreground"), accent)
        if (contrast(foreground, background) < MIN_TEXT_CONTRAST || contrast(foreground, surface) < MIN_TEXT_CONTRAST) return null
        if (contrast(onAccent, accent) < MIN_TEXT_CONTRAST) return null
        val colors = mapOf(
            "app_background" to background,
            "app_surface" to surface,
            "app_surface_translucent" to withAlpha(surface, 0xF2),
            "app_surface_tinted" to tinted,
            "app_foreground" to foreground,
            "app_secondary" to secondary,
            "app_border" to argb.getValue("border-l2"),
            "app_accent" to accent,
            "app_on_accent" to onAccent,
            "app_error" to over(argb.getValue("state-error-primary"), surface),
            "app_success" to over(argb.getValue("state-success-primary"), surface),
        )
        return DshThemePalette(dark, colors)
    }

    /** Parses the bridge message `{ dark: bool, tokens: { name: [r,g,b,a] } }`. */
    fun fromMessage(message: JSONObject): DshThemePalette? {
        val raw = message.optJSONObject("tokens") ?: return null
        val tokens = HashMap<String, IntArray>()
        val names = raw.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (tokens.size >= MAX_TOKEN_COUNT) return null
            val array = raw.optJSONArray(name) ?: return null
            if (array.length() != 4) return null
            tokens[name] = IntArray(4) { index -> array.optInt(index, -1) }
        }
        return fromTokens(tokens, message.optBoolean("dark", false))
    }

    fun store(preferences: SharedPreferences, palette: DshThemePalette) {
        val json = JSONObject()
        for ((name, color) in palette.colors) json.put(name, color)
        val key = PREFERENCE_PREFIX + if (palette.dark) "dark" else "light"
        if (preferences.getString(key, null) == json.toString()) return
        preferences.edit().putString(key, json.toString()).apply()
    }

    @Volatile private var parsedCache: Pair<String, Map<String, Int>?>? = null

    fun load(preferences: SharedPreferences, dark: Boolean): Map<String, Int>? {
        val raw = preferences.getString(PREFERENCE_PREFIX + if (dark) "dark" else "light", null) ?: return null
        parsedCache?.let { (key, value) -> if (key == raw) return value }
        val parsed = runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { put(it, json.getInt(it)) } }
        }.getOrNull()
        parsedCache = raw to parsed
        return parsed
    }

    fun clear(preferences: SharedPreferences) {
        preferences.edit().remove(PREFERENCE_PREFIX + "light").remove(PREFERENCE_PREFIX + "dark").apply()
    }

    fun systemIsDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Synchronized DSH color for an `R.color.app_*` resource, or the resource fallback. */
    fun resolve(context: Context, preferences: SharedPreferences, colorResource: Int): Int {
        val name = runCatching { context.resources.getResourceEntryName(colorResource) }.getOrNull()
        val cached = name?.let { load(preferences, systemIsDark(context))?.get(it) }
        return cached ?: context.getColor(colorResource)
    }

    internal fun over(top: Int, bottom: Int): Int {
        val alpha = (top ushr 24) and 0xFF
        if (alpha == 0xFF) return top
        val a = alpha / 255.0
        fun mix(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return (t * a + b * (1 - a)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun opaque(color: Int, backdrop: Int): Int = over(color, backdrop or (0xFF shl 24))

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    private fun distance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        kotlin.math.abs(((first shr shift) and 0xFF) - ((second shr shift) and 0xFF))
    }

    internal fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

}
