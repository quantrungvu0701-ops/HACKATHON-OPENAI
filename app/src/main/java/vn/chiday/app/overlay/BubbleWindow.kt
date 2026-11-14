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
    private val onLongPress: () -> Unit = {},
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
        // Saved even when never dragged, so restore() has a home position.
        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
        view.contentDescription = service.getString(R.string.bubble_description)
        attachTouch()
        wm.addView(view, lp)
        shown = true
    }

    /**
     * Moves the bubble out of the way when it sits on [target] (the ringed element): to the
     * other side of the screen, or above or below a target that spans the full width. Not
     * saved: [restore] puts it back where the user left it.
     */
    fun avoid(target: android.graphics.Rect, card: android.graphics.Rect? = null) {
        if (!shown) return
        val pad = (12 * density).toInt()
        val zone = android.graphics.Rect(target).apply { inset(-pad, -pad) }
        val cardZone = card?.let { android.graphics.Rect(it).apply { inset(-pad / 2, -pad / 2) } }
        // Fresh screen size every call (rotation), and always start from the user's home
        // position so a bubble displaced for an earlier step does not stay displaced.
        val screen = wm.currentWindowMetrics.bounds
        val margin = (8 * density).toInt()
        val hx = prefs.getInt("x", lp.x)
        val hy = prefs.getInt("y", lp.y)
        val other = if (hx + sizePx / 2 < screen.width() / 2) screen.width() - sizePx - margin else margin
        val ys = mutableListOf(hy, zone.top - sizePx, zone.bottom)
        if (cardZone != null) {
            ys += cardZone.top - sizePx
            ys += cardZone.bottom
        }
        val maxX = (screen.width() - sizePx).coerceAtLeast(0)
        val maxY = (screen.height() - sizePx).coerceAtLeast(0)
        val box = android.graphics.Rect()
        var best: IntArray? = null
        var fallback: IntArray? = null
        loop@ for (y in ys) {
            for (x in intArrayOf(hx, other)) {
                val cx = x.coerceIn(0, maxX)
                val cy = y.coerceIn(0, maxY)
                box.set(cx, cy, cx + sizePx, cy + sizePx)
                if (android.graphics.Rect.intersects(zone, box)) continue
                if (fallback == null) fallback = intArrayOf(cx, cy)
                if (cardZone == null || !android.graphics.Rect.intersects(cardZone, box)) {
                    best = intArrayOf(cx, cy)
                    break@loop
                }
            }
        }
        val pick = best ?: fallback ?: return
        if (pick[0] == lp.x && pick[1] == lp.y) return
        lp.x = pick[0]
        lp.y = pick[1]
        clamp()
        wm.updateViewLayout(view, lp)
    }

    /** Back to the saved position after [avoid]. */
    fun restore() {
        if (!shown) return
        val x = prefs.getInt("x", lp.x)
        val y = prefs.getInt("y", lp.y)
        if (x == lp.x && y == lp.y) return
        lp.x = x
        lp.y = y
        clamp()
        wm.updateViewLayout(view, lp)
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
        var longPressed = false
        // Press and hold (no drag) switches the bubble off: the way out without opening Settings.
        val hold = Runnable {
            longPressed = true
            view.pressedDown = false
            onLongPress()
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    view.pressedDown = true
                    view.postDelayed(hold, HOLD_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.removeCallbacks(hold)
                    }
                    if (dragging && !longPressed) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clamp()
                        wm.updateViewLayout(view, lp)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(hold)
                    view.pressedDown = false
                    if (longPressed) {
                        // already handled: the bubble is gone
                    } else if (dragging) {
                        snapToEdge()
                    } else {
                        onTap()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(hold)
                    view.pressedDown = false
                    true
                }

                else -> false
            }
        }
    }

    private companion object {
        const val HOLD_MS = 700L
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

    /**
     * Ink disc with a highlighter-yellow ring and dot (same mark as the app icon). Ring spins
     * while the model is thinking. During a guided session the disc turns yellow with an ink ring,
     * so the user can see at a glance that Chỉ Đây is following along.
     */
    private class BubbleView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            color = WHITE
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * d
            strokeCap = Paint.Cap.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
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
            val active = state == State.ACTIVE
            disc.color = when {
                pressedDown -> INK_PRESSED
                active -> YELLOW
                else -> INK
            }
            ring.color = if (active) INK else YELLOW
            dot.color = if (active) INK else YELLOW
            canvas.drawCircle(cx, cy + 1.5f * d, r + 1.5f * d, shadow)
            canvas.drawCircle(cx, cy, r, disc)
            // White edge keeps the ink disc visible on dark apps and the yellow one on light apps.
            canvas.drawCircle(cx, cy, r - edge.strokeWidth / 2f, edge)

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

        private companion object {
            const val INK = 0xFF16223B.toInt()
            const val INK_PRESSED = 0xFF2A3A5E.toInt()
            const val YELLOW = 0xFFFFD43B.toInt()
            const val WHITE = 0xFFFFFFFF.toInt()
        }
    }
}
