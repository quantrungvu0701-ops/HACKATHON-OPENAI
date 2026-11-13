package vn.chiday.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen, touch-transparent overlay: dims the screen, rings the target, shows the
 * instruction card. FLAG_NOT_TOUCHABLE means the user's finger lands on the real button
 * underneath. This window never consumes or injects input.
 */
class HighlightWindow(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val view = HighlightView(service)
    private var attached = false

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "ChiDayHighlight"
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** True while a target ring is on screen. */
    val isShowingTarget: Boolean get() = attached && view.target != null

    val isShowing: Boolean get() = attached

    /** Ring [target] (absolute screen pixels, from getBoundsInScreen) and show the card. */
    fun showTarget(target: Rect, title: String, body: String?, footer: String?, caution: Boolean) {
        view.target = Rect(target)
        view.caution = caution
        view.setCard(title, body, footer)
        attach()
    }

    /** Card only, no ring: "not sure", errors. */
    fun showMessage(title: String, body: String? = null) {
        view.target = null
        view.caution = false
        view.setCard(title, body, null)
        attach()
    }

    /** Follow a target that moved (the user scrolled) without redoing the model call. */
    fun moveTarget(target: Rect) {
        if (!isShowingTarget || view.target == target) return
        view.target = Rect(target)
        view.invalidate()
    }

    fun hide() {
        if (!attached) return
        runCatching { wm.removeView(view) }
        attached = false
        view.target = null
    }

    private fun attach() {
        if (attached) {
            view.invalidate()
        } else {
            wm.addView(view, lp)
            attached = true
        }
    }

    private class HighlightView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val sp = resources.displayMetrics.scaledDensity

        var target: Rect? = null
        var caution = false

        private var title: String = ""
        private var body: String? = null
        private var footer: String? = null
        private var titleLayout: StaticLayout? = null
        private var bodyLayout: StaticLayout? = null
        private var footerLayout: StaticLayout? = null
        private var layoutWidth = 0

        private val dim = Paint().apply { color = 0x73000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND
        }
        private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDF8.toInt() }
        private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B2118.toInt(); textSize = 24f * sp; typeface = Typeface.DEFAULT_BOLD
        }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2118.toInt(); textSize = 19f * sp }
        private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B5B4D.toInt(); textSize = 15f * sp; typeface = Typeface.DEFAULT_BOLD
        }

        private val origin = IntArray(2)
        private val hole = RectF()
        private val path = Path()
        private val cardRect = RectF()

        fun setCard(title: String, body: String?, footer: String?) {
            this.title = title
            this.body = body?.takeIf { it.isNotBlank() && it != title }
            this.footer = footer?.takeIf { it.isNotBlank() }
            layoutWidth = 0 // rebuild text layouts on next draw
            invalidate()
        }

        private fun buildLayouts(width: Int) {
            if (width == layoutWidth || width <= 0) return
            layoutWidth = width
            fun make(text: String, paint: TextPaint, maxLines: Int) =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.12f)
                    .setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            titleLayout = make(title, titlePaint, 3)
            bodyLayout = body?.let { make(it, bodyPaint, 5) }
            footerLayout = footer?.let { make(it, footerPaint, 1) }
        }

        override fun onDraw(canvas: Canvas) {
            // getBoundsInScreen is in absolute screen pixels; convert to this view's coordinates.
            getLocationOnScreen(origin)
            val accent = if (caution) AMBER else BRAND
            val t = target

            if (t != null) {
                val pad = 8f * d
                hole.set(t.left - origin[0] - pad, t.top - origin[1] - pad, t.right - origin[0] + pad, t.bottom - origin[1] + pad)
                val radius = min(min(hole.width(), hole.height()) / 2f, 22f * d)

                // Dim everything except the target.
                path.reset()
                path.fillType = Path.FillType.EVEN_ODD
                path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                path.addRoundRect(hole, radius, radius, Path.Direction.CW)
                canvas.drawPath(path, dim)

                // Solid ring with a white halo so it reads on any background.
                halo.strokeWidth = 9f * d
                canvas.drawRoundRect(hole, radius, radius, halo)
                ring.color = accent
                ring.alpha = 255
                ring.strokeWidth = 5f * d
                canvas.drawRoundRect(hole, radius, radius, ring)

                // Pulse: a second ring that grows and fades.
                val phase = (SystemClock.uptimeMillis() % PULSE_MS) / PULSE_MS.toFloat()
                val grow = phase * 22f * d
                ring.alpha = ((1f - phase) * 200).toInt()
                ring.strokeWidth = 4f * d
                hole.inset(-grow, -grow)
                canvas.drawRoundRect(hole, radius + grow, radius + grow, ring)
                hole.inset(grow, grow)

                if (hole.width() < SMALL_DP * d || hole.height() < SMALL_DP * d) drawArrow(canvas, accent, phase)
            }

            drawCard(canvas, accent)
            if (t != null) postInvalidateOnAnimation()
        }

        /** An arrow pointing at a small target from the direction of the screen centre. */
        private fun drawArrow(canvas: Canvas, accent: Int, phase: Float) {
            val cx = hole.centerX()
            val cy = hole.centerY()
            var ang = atan2(height / 2f - cy, width / 2f - cx)
            if (hypot(width / 2f - cx, height / 2f - cy) < 40f * d) ang = (Math.PI / 2).toFloat()
            val bob = (sin(phase * 2 * Math.PI).toFloat() + 1f) * 5f * d
            val tipDist = max(hole.width(), hole.height()) / 2f + 14f * d + bob
            val len = 64f * d
            val half = 22f * d
            val shaft = 9f * d
            val ux = cos(ang)
            val uy = sin(ang)
            val px = -uy
            val py = ux
            val tipX = cx + ux * tipDist
            val tipY = cy + uy * tipDist
            val neckX = tipX + ux * half * 1.3f
            val neckY = tipY + uy * half * 1.3f
            val tailX = tipX + ux * len
            val tailY = tipY + uy * len
            path.reset()
            path.fillType = Path.FillType.WINDING
            path.moveTo(tipX, tipY)
            path.lineTo(neckX + px * half, neckY + py * half)
            path.lineTo(neckX + px * shaft, neckY + py * shaft)
            path.lineTo(tailX + px * shaft, tailY + py * shaft)
            path.lineTo(tailX - px * shaft, tailY - py * shaft)
            path.lineTo(neckX - px * shaft, neckY - py * shaft)
            path.lineTo(neckX - px * half, neckY - py * half)
            path.close()
            arrow.color = accent
            canvas.drawPath(path, arrow)
            canvas.drawPath(path, arrowEdge)
        }

        private fun drawCard(canvas: Canvas, accent: Int) {
            if (title.isEmpty()) return
            val margin = 16f * d
            val padH = 20f * d
            val padV = 16f * d
            buildLayouts((width - 2 * margin - 2 * padH).toInt())
            val tl = titleLayout ?: return
            val gap = 6f * d
            var contentH = tl.height.toFloat()
            footerLayout?.let { contentH += it.height + gap }
            bodyLayout?.let { contentH += it.height + gap }
            val cardH = contentH + 2 * padV

            // Keep the card away from the target: bottom of the screen unless the target is there.
            val t = target
            val targetCentreY = if (t != null) (t.centerY() - origin[1]).toFloat() else 0f
            val atBottom = t == null || targetCentreY < height * 0.55f
            val top = if (atBottom) height - cardH - 72f * d else 56f * d
            cardRect.set(margin, top, width - margin, top + cardH)

            val r = 22f * d
            canvas.drawRoundRect(cardRect, r, r, cardFill)
            cardEdge.color = accent
            canvas.drawRoundRect(cardRect, r, r, cardEdge)

            canvas.save()
            canvas.translate(cardRect.left + padH, cardRect.top + padV)
            footerLayout?.let {
                it.draw(canvas)
                canvas.translate(0f, it.height + gap)
            }
            tl.draw(canvas)
            canvas.translate(0f, tl.height + gap)
            bodyLayout?.draw(canvas)
            canvas.restore()
        }

        private companion object {
            const val BRAND = 0xFFFF5A1F.toInt()
            const val AMBER = 0xFFFFB300.toInt()
            const val PULSE_MS = 1400L
            const val SMALL_DP = 72f
        }
    }
}
