package io.github.sayach.dshmobile

import android.animation.TimeInterpolator
import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shared measurements for the native pages.
 *
 * Every screen uses one 8dp rhythm, one type scale and one set of corner radii so the
 * native surfaces read like the DSH shell instead of a stack of platform defaults.
 * Colors still come from [DshNativeTheme]; this file only owns geometry and motion.
 */
internal object DshNativeDimens {
    /** Corner radius of a list row or a grouped card. */
    const val CARD_RADIUS = 20
    /** Corner radius of a bottom sheet's leading edge. */
    const val SHEET_RADIUS = 28
    /** Corner radius of a primary action. */
    const val PRIMARY_RADIUS = 16
    /** Corner radius of a secondary action or a text field. */
    const val SECONDARY_RADIUS = 14
    /** Grid unit. All spacing is a multiple of it. */
    const val GRID = 8
    /** Smallest accepted tap target. */
    const val TOUCH_TARGET = 48
    /** Short, non-decorative transition used for page fades and sheet entry. */
    const val MOTION_MS = 200L
    /** Widest column a form is allowed to occupy on a tablet. */
    const val CONTENT_MAX_WIDTH = 560

    /** Space above and below the page content. */
    const val PAGE_VERTICAL = 32
    /** Space between the screen edge and the content column. */
    const val PAGE_GUTTER = 24

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun Int.dp(context: Context): Int = dp(context, this)

    fun px(resources: Resources, value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

/** Native type scale, in sp, always with an explicit weight and line height. */
internal object DshNativeType {
    const val DISPLAY = 30f
    const val TITLE = 24f
    const val SECTION = 17f
    const val CARD_TITLE = 18f
    const val BODY = 15f
    const val CAPTION = 13f
    const val LABEL = 12f

    const val LINE_DISPLAY = 1.2f
    const val LINE_TITLE = 1.25f
    const val LINE_BODY = 1.45f
    const val LINE_CAPTION = 1.35f
}

/** Centralised motion policy so a system request to reduce animation is honoured once. */
internal object DshNativeMotion {
    fun animationsEnabled(context: Context): Boolean {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val transition = Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        return scale > 0f && transition > 0f
    }

    fun interpolator(): TimeInterpolator = DecelerateInterpolator()

    /** Fades a view in unless the system asks for static content. */
    fun fadeIn(view: View, context: Context, startDelayMs: Long = 0L) {
        if (!animationsEnabled(context)) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setStartDelay(startDelayMs)
            .setDuration(DshNativeDimens.MOTION_MS).setInterpolator(interpolator()).start()
    }

    /** Runs [onEnd] immediately when animation is disabled. */
    fun fadeOut(view: View, context: Context, onEnd: (() -> Unit)? = null) {
        if (!animationsEnabled(context)) {
            if (onEnd != null) onEnd() else view.alpha = 0f
            return
        }
        view.animate().alpha(0f).setDuration(DshNativeDimens.MOTION_MS)
            .setInterpolator(interpolator()).withEndAction { onEnd?.invoke() }.start()
    }

    /** 150–240ms pulse used by the waiting indicator instead of a spinning platform spinner. */
    fun pulse(view: View, context: Context) {
        if (!animationsEnabled(context)) return
        val from = 0.55f
        val to = 1f
        view.alpha = from
        ValueAnimator.ofFloat(from, to).apply {
            duration = DshNativeDimens.MOTION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
    }
}

/** Drawable factories that keep strokes, radii and press feedback consistent. */
internal object DshNativeShapes {
    fun surface(context: Context, fillColor: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = DshNativeDimens.dp(context, radiusDp).toFloat()
            if (strokeColor != null) setStroke(DshNativeDimens.dp(context, 1), strokeColor)
        }

    /** Rounds only the top corners, for a bottom sheet panel. */
    fun sheet(context: Context, fillColor: Int, radiusDp: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadii = floatArrayOf(
                DshNativeDimens.dp(context, radiusDp).toFloat(), DshNativeDimens.dp(context, radiusDp).toFloat(),
                DshNativeDimens.dp(context, radiusDp).toFloat(), DshNativeDimens.dp(context, radiusDp).toFloat(),
                0f, 0f, 0f, 0f,
            )
            setStroke(DshNativeDimens.dp(context, 1), strokeColor)
        }

    fun pressable(context: Context, fillColor: Int, radiusDp: Int, rippleColor: Int, strokeColor: Int? = null): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            surface(context, fillColor, radiusDp, strokeColor),
            null,
        )

    /** A ring used behind the DSH mark on the waiting page. */
    fun ring(context: Context, fillColor: Int, radiusDp: Int, strokeColor: Int): LayerDrawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(DshNativeDimens.dp(context, 1), strokeColor)
        }
        return LayerDrawable(arrayOf(ring))
    }

    fun dot(context: Context, color: Int, diameterDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(DshNativeDimens.dp(context, diameterDp), DshNativeDimens.dp(context, diameterDp))
    }
}

/**
 * Applies one typographic rule to any text view: size, weight, line height and colour
 * all come from a single place, so individual screens cannot drift apart.
 */
internal fun TextView.applyDshType(
    sizeSp: Float,
    style: Int,
    color: Int,
    lineHeight: Float? = null,
    letterSpacingEm: Float = 0f,
) {
    textSize = sizeSp
    setTypeface(Typeface.DEFAULT, style)
    setTextColor(color)
    includeFontPadding = false
    setLineSpacing(0f, lineHeight ?: when {
        sizeSp >= 24f -> DshNativeType.LINE_TITLE
        sizeSp >= 15f -> DshNativeType.LINE_BODY
        else -> DshNativeType.LINE_CAPTION
    })
    this.letterSpacing = letterSpacingEm
    if (paintFlags and Paint.SUBPIXEL_TEXT_FLAG == 0) paintFlags = paintFlags or Paint.SUBPIXEL_TEXT_FLAG
}

/** Scales a single line of icon at a fixed frame so rows keep an identical right edge. */
/** Lightweight alternative to a text watcher: runs [block] after every text change. */
internal fun TextView.afterDshTextChanged(block: (CharSequence) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = block(s ?: "")

        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
}

internal fun ImageView.frameIcon(sizeDp: Int, context: Context) {
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = ViewGroup.LayoutParams(DshNativeDimens.dp(context, sizeDp), DshNativeDimens.dp(context, sizeDp))
}

/**
 * The thin ring loader DSH's own web shell shows while it boots: a hairline track with a
 * quarter arc turning at a steady pace. The native waiting page uses the same mark, size and
 * position so handing over to the web loader is seamless. Like the web loader it keeps turning
 * even when the system animator scale is off.
 */
internal class DshRingSpinner(context: Context, trackColor: Int, arcColor: Int) : View(context) {
    private val stroke = DshNativeDimens.dp(context, 2).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = trackColor
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        // DSH draws the arc with a conic gradient: square ends, not rounded caps.
        strokeCap = Paint.Cap.BUTT
        color = arcColor
    }
    private val bounds = android.graphics.RectF()
    private var spinning = false
    private var sweep = START_SWEEP
    private var targetSweep = START_SWEEP

    /** Follows DSH's boot progress: the arc grows from 72 to 288 degrees as plugins activate. */
    fun setProgressSweep(degrees: Float) {
        targetSweep = degrees.coerceIn(START_SWEEP, END_SWEEP)
        if (!spinning) {
            sweep = targetSweep
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val inset = stroke / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        // Driven by the frame clock rather than ValueAnimator: many phones run with the system
        // animator scale at 0 (developer options, battery savers), which froze the ring into a
        // plain circle. DSH's web loader keeps turning in that case, so this one does too.
        val now = android.os.SystemClock.uptimeMillis()
        val angle = -90f + (now % SPIN_MS) * 360f / SPIN_MS
        // The arc breathes between short and long while it turns, the same motion as the
        // style the app injects into DSH's own boot spinner, so it never reads as a plain circle.
        val phase = (now % BREATH_MS).toFloat() / BREATH_MS
        val wave = (1f - kotlin.math.cos(phase * 2f * Math.PI.toFloat())) / 2f
        val length = MIN_BREATH + (MAX_BREATH - MIN_BREATH) * wave
        canvas.drawArc(bounds, angle - length / 2f, length, false, arc)
        if (spinning) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spinning = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        spinning = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val shouldSpin = isVisible && isAttachedToWindow
        if (shouldSpin && !spinning) postInvalidateOnAnimation()
        spinning = shouldSpin
    }

    private companion object {
        // DSH's web loader: one turn per 0.8s, arc 72deg + progress x 216deg.
        const val SPIN_MS = 800L
        const val START_SWEEP = 72f
        const val END_SWEEP = 288f
        const val BREATH_MS = 1_600L
        const val MIN_BREATH = 30f
        const val MAX_BREATH = 270f
    }
}

/**
 * A quiet hairline pill for a low-emphasis action such as cancelling a connection: transparent,
 * one border, secondary text. It never competes with the page it sits on.
 */
internal fun quietPill(context: Context, text: CharSequence, textColor: Int, borderColor: Int, pressColor: Int): TextView =
    TextView(context).apply {
        this.text = text
        gravity = android.view.Gravity.CENTER
        applyDshType(DshNativeType.LABEL, Typeface.NORMAL, textColor)
        textSize = 14f
        letterSpacing = 0.02f
        minHeight = DshNativeDimens.dp(context, 40)
        minimumHeight = DshNativeDimens.dp(context, 40)
        setPadding(DshNativeDimens.dp(context, 22), 0, DshNativeDimens.dp(context, 22), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(pressColor),
            DshNativeShapes.surface(context, android.graphics.Color.TRANSPARENT, 999, borderColor),
            DshNativeShapes.surface(context, android.graphics.Color.BLACK, 999),
        )
        isClickable = true
        isFocusable = true
    }
