package com.hatchbot

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Small draggable bar floating over the game: start/stop, screenshot, status, hide. */
class OverlayPanel(private val service: BotAccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(160)
    }

    private val playButton = button("▶") { service.toggleBot() }

    private val status = TextView(service).apply {
        text = "Ready"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxWidth = dp(190)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(6), 0, dp(6), 0)
    }

    private val root = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xDD20202A.toInt())
        }
        setPadding(dp(4), dp(2), dp(4), dp(2))
        addView(playButton)
        addView(button("📷") { service.snap() })
        addView(status)
        addView(button("✕") { service.hidePanel() })
    }

    @Volatile
    private var bounds = Rect()

    @Volatile
    private var visible = true

    fun show() {
        windowManager.addView(root, params)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        enableDrag(status)
    }

    fun remove() {
        windowManager.removeView(root)
    }

    fun setRunning(running: Boolean) {
        playButton.text = if (running) "■" else "▶"
    }

    fun setStatus(text: String) {
        status.text = text
    }

    fun setVisible(show: Boolean) {
        visible = show
        root.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    /** True if a tap at this screen point would land on the bar instead of the game. */
    fun covers(x: Float, y: Float) = visible && bounds.contains(x.toInt(), y.toInt())

    private fun updateBounds() {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        bounds = Rect(location[0], location[1], location[0] + root.width, location[1] + root.height)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> root.post { updateBounds() }
            }
            true
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(service).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * density).toInt()
}
