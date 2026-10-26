package io.github.sayach.dshmobile

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A themed bottom sheet for the native menus.
 *
 * Platform AlertDialogs follow the AppTheme, not the synchronized DSH palette, so a
 * list of items ends up as a default platform slab next to a DSH-tinted page. This
 * sheet draws every part from the same palette as the rest of the native UI: a scrim
 * over the page, a rounded surface with one hairline border, a quiet title, and rows
 * that carry a check mark plus an optional second line describing the choice.
 */
internal class DshActionSheet(context: Context) : Dialog(context, R.style.DshActionSheet) {

    internal class Row(
        val label: CharSequence,
        val description: CharSequence?,
        val selected: Boolean,
        val destructive: Boolean,
        val onClick: () -> Unit,
    )

    private val rows = mutableListOf<Row>()
    private var sheetTitle: CharSequence? = null
    private var sheetMessage: CharSequence? = null
    private var confirmLabel: CharSequence? = null
    private var confirmDisabled = true
    private var confirmDestructive = false
    private var onConfirm: (() -> Unit)? = null
    private var alternateLabel: CharSequence? = null
    private var onAlternate: (() -> Unit)? = null
    private var inputValue: CharSequence? = null
    private var inputHint: CharSequence? = null
    private var inputType = InputType.TYPE_CLASS_TEXT
    private var inputImeOptions = EditorInfo.IME_ACTION_DONE
    private var inputDescription: CharSequence? = null
    private var inputListener: ((String) -> Unit)? = null
    private var dismissHandler: (() -> Unit)? = null
    private var shownHandler: (() -> Unit)? = null

    private lateinit var panel: LinearLayout
    private lateinit var scrim: View

    /** The field of the sheet currently on screen, so a caller can read or validate it. */
    var sheetInput: EditText? = null
        private set
    private var confirmView: TextView? = null

    class Builder(private val context: Context) {
        private val sheet = DshActionSheet(context)

        fun title(text: CharSequence?): Builder = apply { sheet.sheetTitle = text }
        fun title(resource: Int): Builder = title(context.getString(resource) as CharSequence)
        fun message(text: CharSequence?): Builder = apply { sheet.sheetMessage = text }
        fun message(resource: Int): Builder = message(context.getString(resource) as CharSequence)

        /** Adds a row. A selected row renders a check mark and a stronger label. */
        fun row(
            label: CharSequence,
            description: CharSequence? = null,
            selected: Boolean = false,
            destructive: Boolean = false,
            onClick: () -> Unit,
        ): Builder = apply {
            sheet.rows.add(Row(label, description, selected, destructive, onClick))
        }

        /** Adds a primary confirmation action below the rows. */
        fun confirm(
            label: CharSequence,
            enabled: Boolean = true,
            destructive: Boolean = false,
            onClick: () -> Unit,
        ): Builder = apply {
            sheet.confirmLabel = label
            sheet.confirmDisabled = !enabled
            sheet.confirmDestructive = destructive
            sheet.onConfirm = onClick
        }

        fun confirm(
            label: Int,
            enabled: Boolean = true,
            destructive: Boolean = false,
            onClick: () -> Unit,
        ): Builder = confirm(context.getString(label) as CharSequence, enabled, destructive, onClick)

        /** Adds a quiet action below the rows: cancel, skip or the second of two choices. */
        fun alternate(label: Int, onClick: () -> Unit): Builder = alternate(context.getString(label) as CharSequence, onClick)
        fun alternate(label: CharSequence, onClick: () -> Unit): Builder = apply {
            sheet.alternateLabel = label
            sheet.onAlternate = onClick
        }

        /**
         * Places an editable value above the rows. [onTextChanged] receives every keystroke so a
         * caller can validate live instead of rejecting the value after the sheet closes.
         */
        fun input(
            value: CharSequence,
            hint: CharSequence,
            inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            imeOptions: Int = EditorInfo.IME_ACTION_DONE,
            contentDescription: CharSequence = hint,
            onTextChanged: ((String) -> Unit)? = null,
        ): Builder = apply {
            sheet.inputListener = onTextChanged
            sheet.inputValue = value
            sheet.inputHint = hint
            sheet.inputType = inputType
            sheet.inputImeOptions = imeOptions
            sheet.inputDescription = contentDescription
        }

        fun onDismiss(block: () -> Unit): Builder = apply { sheet.dismissHandler = block }
        fun onShown(block: () -> Unit): Builder = apply { sheet.shownHandler = block }

        fun build(): DshActionSheet {
            require(sheet.sheetTitle != null || sheet.rows.isNotEmpty() || sheet.confirmLabel != null) {
                "A DSH action sheet needs a title, a row or a confirmation"
            }
            return sheet
        }

        /** Builds the sheet and shows it. */
        fun show(): DshActionSheet = build().apply { show() }
    }

    /** The typed value of the sheet's input, or null when it has none. */
    fun inputText(): String? = sheetInput?.text?.toString()

    /** Highlights the confirmation when the caller validates its input live. */
    fun setConfirmEnabled(enabled: Boolean) {
        confirmView?.let { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.45f
            view.isClickable = enabled
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = window ?: return
        // A full-screen, edge-to-edge window: the sheet draws its own scrim over the whole
        // display, including behind the status and navigation bars, instead of the platform
        // floating-dialog frame that clips it to a centred column.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setGravity(Gravity.FILL)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        matchOwnerSystemBarIcons(window)
        setCanceledOnTouchOutside(true)

        scrim = View(context).apply {
            setBackgroundColor(withOpacity(foregroundColor(), SCRIM_ALPHA))
            isClickable = true
            setOnClickListener { dismiss() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(24), pad(24), pad(24))
            background = DshNativeShapes.sheet(context, surfaceColor(), DshNativeDimens.SHEET_RADIUS, borderColor())
            isClickable = true
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, pad(48), 0, 0)
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        val content = FrameLayout(context).apply {
            setPadding(0, 0, 0, 0)
            addView(scrim, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        }
        setContentView(content)
        buildContent()
        applyInsets(content, scroll)
    }

    private fun buildContent() {
        val foreground = foregroundColor()
        panel.addView(View(context).apply {
            background = DshNativeShapes.surface(context, borderColor(), 999)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(pad(36), pad(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = pad(20)
        })
        sheetTitle?.let { title ->
            panel.addView(TextView(context).apply {
                text = title
                applyDshType(DshNativeType.SECTION, Typeface.BOLD, foreground, DshNativeType.LINE_TITLE)
            })
            panel.addView(spacerView(12))
        }
        sheetMessage?.let { message ->
            panel.addView(TextView(context).apply {
                text = message
                applyDshType(DshNativeType.CAPTION, Typeface.NORMAL, secondaryColor())
            })
            panel.addView(spacerView(16))
        }
        if (sheetTitle != null && rows.isEmpty() && confirmLabel == null) panel.addView(spacerView(8))
        inputValue?.let { value ->
            val field = EditText(context).apply {
                setText(value)
                setSelection(text.length)
                hint = inputHint
                this.inputType = this@DshActionSheet.inputType
                imeOptions = this@DshActionSheet.inputImeOptions
                isSingleLine = true
                applyDshType(16f, Typeface.NORMAL, foreground)
                minHeight = pad(52)
                setPadding(pad(16), 0, pad(16), 0)
                background = DshNativeShapes.surface(context, tintedColor(), DshNativeDimens.SECONDARY_RADIUS, borderColor())
                contentDescription = inputDescription
            }
            sheetInput = field
            inputListener?.let { listener ->
                field.afterDshTextChanged { listener(it.toString()) }
            }
            panel.addView(field, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            panel.addView(spacerView(16))
        }
        rows.forEachIndexed { index, row ->
            panel.addView(SheetRowView(context, row, foreground))
            if (index != rows.lastIndex) panel.addView(hairline())
        }
        if (rows.isNotEmpty() && (confirmLabel != null || alternateLabel != null)) panel.addView(spacerView(16))
        alternateLabel?.let { label ->
            panel.addView(sheetButton(label, primary = false, destructive = false, onClick = { onAlternate?.invoke() }))
            panel.addView(spacerView(8))
        }
        confirmLabel?.let { label ->
            val button = sheetButton(label, primary = true, destructive = confirmDestructive, onClick = { onConfirm?.invoke() })
            if (confirmDisabled) {
                button.isEnabled = false
                button.alpha = 0.45f
            }
            confirmView = button
            panel.addView(button)
        }
    }

    /** A menu row: label, optional description and a check mark for the chosen option. */
    private inner class SheetRowView(
        context: Context,
        private val row: Row,
        private val foreground: Int,
    ) : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(4), pad(12), pad(4), pad(12))
            minimumHeight = pad(DshNativeDimens.TOUCH_TARGET)
            background = DshNativeShapes.pressable(context, Color.TRANSPARENT, 12, tintedColor(), null)
            isClickable = true
            isFocusable = true
            val column = LinearLayout(context).apply { orientation = VERTICAL }
            val labelView = TextView(context)
            labelView.text = row.label.toString()
            labelView.applyDshType(
                DshNativeType.BODY,
                if (row.selected) Typeface.BOLD else Typeface.NORMAL,
                if (row.destructive) errorColor() else foreground,
            )
            column.addView(labelView)
            val note: CharSequence? = row.description
            if (note != null) {
                column.addView(spacerView(2))
                val noteView = TextView(context)
                noteView.setText(note)
                noteView.applyDshType(DshNativeType.LABEL, Typeface.NORMAL, secondaryColor())
                column.addView(noteView)
            }
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row.selected) {
                addView(
                    checkView(foreground),
                    LinearLayout.LayoutParams(pad(20), pad(20)).apply { marginStart = pad(12) },
                )
            }
            setOnClickListener {
                dismissAfterAction = true
                row.onClick()
                if (dismissAfterAction) dismiss()
            }
        }
    }

    private fun sheetButton(label: CharSequence, primary: Boolean, destructive: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            applyDshType(DshNativeType.SECTION, Typeface.BOLD, when {
                primary && destructive -> errorColor()
                primary -> onAccentColor()
                destructive -> errorColor()
                else -> foregroundColor()
            })
            minHeight = pad(52)
            setPadding(pad(16), pad(14), pad(16), pad(14))
            background = if (primary) {
                DshNativeShapes.pressable(context, accentColor(), DshNativeDimens.PRIMARY_RADIUS, tintedColor(), null)
            } else {
                DshNativeShapes.pressable(context, tintedColor(), DshNativeDimens.SECONDARY_RADIUS, accentColor(), borderColor())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun hairline(): View = View(context).apply {
        setBackgroundColor(borderColor())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, pad(1)))
    }

    /** The checked marker of a chosen option in a single-choice sheet. */
    private fun checkView(tint: Int): ImageView = ImageView(context).apply {
        setImageDrawable(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_check))
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun spacerView(heightDp: Int): View = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(0, pad(heightDp))
    }

    override fun onStart() {
        super.onStart()
        // Some OEM dialog frames re-apply their own width after the first layout.
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** Status and navigation bar icons keep the contrast the page underneath already chose. */
    private fun matchOwnerSystemBarIcons(window: Window) {
        val owner = (ownerActivity ?: context.findActivity())?.window ?: return
        val ownerController = WindowCompat.getInsetsController(owner, owner.decorView)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = ownerController.isAppearanceLightStatusBars
        controller.isAppearanceLightNavigationBars = ownerController.isAppearanceLightNavigationBars
    }

    private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    override fun show() {
        super.show()
        if (!::panel.isInitialized) return
        shownHandler?.invoke()
        if (!DshNativeMotion.animationsEnabled(context)) return
        scrim.alpha = 0f
        scrim.animate().alpha(1f)
            .setDuration(DshNativeDimens.MOTION_MS)
            .setInterpolator(DshNativeMotion.interpolator())
            .start()
        val host = panel.parent as? View ?: panel
        host.translationY = pad(24).toFloat()
        host.animate().translationY(0f)
            .setDuration(DshNativeDimens.MOTION_MS)
            .setInterpolator(DshNativeMotion.interpolator())
            .start()
    }

    override fun dismiss() {
        val handler = dismissHandler
        dismissHandler = null
        super.dismiss()
        handler?.invoke()
    }

    /** Keeps the last row above the navigation bar and the keyboard. */
    private fun applyInsets(content: View, scroll: ScrollView) {
        content.setOnApplyWindowInsetsListener { _, insets ->
            val edges = resolveWebViewSafeArea(insets)
            val bottom = edges.bottom + pad(16)
            val current = scroll.paddingBottom
            if (current != bottom) scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bottom)
            insets
        }
        content.requestApplyInsets()
    }

    private fun pad(valueDp: Int): Int = DshNativeDimens.dp(context, valueDp)

    private fun foregroundColor(): Int = themeColor(R.color.app_foreground)
    private fun secondaryColor(): Int = themeColor(R.color.app_secondary)
    private fun surfaceColor(): Int = themeColor(R.color.app_surface)
    private fun tintedColor(): Int = themeColor(R.color.app_surface_tinted)
    private fun borderColor(): Int = themeColor(R.color.app_border)
    private fun accentColor(): Int = themeColor(R.color.app_accent)
    private fun onAccentColor(): Int = themeColor(R.color.app_on_accent)
    private fun errorColor(): Int = themeColor(R.color.app_error)

    private fun themeColor(resource: Int): Int =
        DshNativeTheme.resolve(context, context.getSharedPreferences("dsh_mobile", Context.MODE_PRIVATE), resource)

    private fun withOpacity(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    /**
     * Set by a row that needs to stay on screen after its click, for example a confirmation row
     * whose only job is to read its own value.
     */
    internal var dismissAfterAction = true

    /** Closes an open sheet from a caller that holds it, for example after persisting a choice. */
    internal fun close() {
        if (isShowing) dismiss()
    }

    private companion object {
        const val SCRIM_ALPHA = 0x59
    }
}
