package vn.chiday.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import vn.chiday.app.R
import kotlin.math.abs

/**
 * The draggable floating button. Lives in a TYPE_ACCESSIBILITY_OVERLAY window created with
 * the service context, so it needs no SYSTEM_ALERT_WINDOW permission.
 */
class BubbleWindow(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
) {
    enum class State { IDLE, THINKING, ACTIVE }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = service.getSharedPreferences("bubble", Context.MODE_PRIVATE)
    private val density = service.resources.displayMetrics.density
    private val sizePx = (64 * density).toInt()
    private val view = BubbleView(service)
    private var shown = false

    private val lp = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "ChiDayBubble"
    }

    var state: State
        get() = view.state
        set(value) {
            view.state = value
        }

    /** Bubble rectangle in absolute screen pixels; the overlay avoids covering it. */
    val boundsOnScreen: android.graphics.Rect
        get() = android.graphics.Rect(lp.x, lp.y, lp.x + sizePx, lp.y + sizePx)

    fun show() {
        if (shown) return
        val screen = wm.currentWindowMetrics.bounds
        lp.x = prefs.getInt("x", screen.width() - sizePx - (8 * density).toInt())
        lp.y = prefs.getInt("y", (screen.height() * 0.55f).toInt())
        clamp()
        view.contentDescription = service.getString(R.string.bubble_description)
        attachTouch()
        wm.addView(view, lp)
        shown = true
    }

    /** Re-adds the window so it sits above an overlay that was attached after it. */
    fun bringToFront() {
        if (!shown) return
        runCatching { wm.removeViewImmediate(view) }
        wm.addView(view, lp)
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(view) }
        shown = false
    }

    private fun clamp() {
        val screen = wm.currentWindowMetrics.bounds
        lp.x = lp.x.coerceIn(0, (screen.width() - sizePx).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screen.height() - sizePx).coerceAtLeast(0))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch() {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    view.pressedDown = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clamp()
                        wm.updateViewLayout(view, lp)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.pressedDown = false
                    if (dragging) {
                        snapToEdge()
                    } else {
                        onTap()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.pressedDown = false
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val screen = wm.currentWindowMetrics.bounds
        val margin = (8 * density).toInt()
        val centre = lp.x + sizePx / 2
        lp.x = if (centre < screen.width() / 2) margin else screen.width() - sizePx - margin
        clamp()
        wm.updateViewLayout(view, lp)
        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
    }

    /** Orange disc with a white ring and dot. Ring spins while the model is thinking. */
    private class BubbleView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * d
            strokeCap = Paint.Cap.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val arc = RectF()

        var state: State = State.IDLE
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var pressedDown = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - 4 * d
            disc.color = when {
                pressedDown -> 0xFFA6360B.toInt()
                state == State.ACTIVE -> 0xFF2B8A3E.toInt()
                else -> 0xFFD9480F.toInt()
            }
            canvas.drawCircle(cx, cy + 1.5f * d, r + 1.5f * d, shadow)
            canvas.drawCircle(cx, cy, r, disc)

            val rr = r * 0.52f
            if (state == State.THINKING) {
                arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
                val start = (SystemClock.uptimeMillis() % 900L) / 900f * 360f
                canvas.drawArc(arc, start, 250f, false, ring)
                postInvalidateOnAnimation()
            } else {
                canvas.drawCircle(cx, cy, rr, ring)
            }
            canvas.drawCircle(cx, cy, r * 0.2f, dot)
        }
    }
}
