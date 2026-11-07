package dev.redflag.app.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout

/**
 * The two overlay windows of one Chrome cover (docs/superpowers/specs/2026-11-05-check-pill-design.md §4):
 *  - [stage]: holds the hidden WebView. Full size so pages lay out like a real tab, but invisible (window alpha 0)
 *    and never touchable. It does not change while the cover grows or shrinks.
 *  - [surface]: what the user sees. Pill = its own size, touches elsewhere go to Chrome (NOT_TOUCH_MODAL);
 *    full = the whole screen, swallowing every touch (the old cover).
 */
class CoverWindows(context: Context, private val wm: WindowManager) {
    val stage = FrameLayout(context)
    val surface = FrameLayout(context)

    private val stageLp = WindowManager.LayoutParams(
        MATCH_PARENT, MATCH_PARENT, TYPE_ACCESSIBILITY_OVERLAY,
        FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply { alpha = 0f }

    private val lp = WindowManager.LayoutParams(
        MATCH_PARENT, MATCH_PARENT, TYPE_ACCESSIBILITY_OVERLAY, FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    var full: Boolean = true
        private set
    private var keyboard = false
    private var added = false

    /** Screen y (px) of the pill's top edge: just below Chrome's toolbar, so the address stays readable. */
    var pillY: Int = 0

    fun add(full: Boolean) {
        this.full = full
        layout()
        runCatching { wm.addView(stage, stageLp) }
        runCatching { wm.addView(surface, lp) }
        added = true
    }

    fun setFull(full: Boolean) {
        if (full == this.full) return
        this.full = full
        apply()
    }

    /** The cover takes key input only while the context text field is on screen (keyboard). */
    fun setKeyboard(on: Boolean) {
        if (on == keyboard) return
        keyboard = on
        apply()
    }

    private fun apply() {
        if (!added) return
        layout()
        runCatching { wm.updateViewLayout(surface, lp) }
    }

    private fun layout() {
        if (full) {
            lp.width = MATCH_PARENT; lp.height = MATCH_PARENT
            lp.gravity = Gravity.TOP or Gravity.START; lp.y = 0
            // touchable on purpose: the full cover swallows every tap until the verdict is in
            lp.flags = FLAG_LAYOUT_IN_SCREEN or (if (keyboard) 0 else FLAG_NOT_FOCUSABLE)
        } else {
            lp.width = WRAP_CONTENT; lp.height = WRAP_CONTENT
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.y = pillY
            lp.flags = FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL
        }
    }

    fun remove() {
        if (!added) return
        added = false
        runCatching { wm.removeView(surface) }
        runCatching { wm.removeView(stage) }
    }
}
