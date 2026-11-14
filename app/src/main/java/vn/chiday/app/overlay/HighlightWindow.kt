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
    val isShowingTarget: Boolean get() = isShowing && view.target != null

    val isShowing: Boolean get() = attached && view.visibility == View.VISIBLE

    /**
     * Adds the window once, hidden. It is attached BEFORE the bubble so the bubble always sits
     * above it, and it then stays attached for the life of the service: showing and hiding only
     * toggles visibility. Adding and removing windows on every step made the render thread
     * create a new surface each time, for this window and for the re-added bubble.
     */
    fun attach() {
        if (attached) return
        view.visibility = View.GONE
        wm.addView(view, lp)
        attached = true
    }

    fun detach() {
        if (!attached) return
        runCatching { wm.removeViewImmediate(view) }
        attached = false
        view.target = null
    }

    /**
     * Ring [target] (absolute screen pixels, from getBoundsInScreen) and show the card.
     * [footer] is the small line above the title ("Bước 2 trên 3 · chưa kiểm chứng"); [step] and
     * [total] draw the progress segments under it. [mark] is the target's on-screen label: where
     * it appears in the title it gets a highlighter background, so the eye finds the button name.
     * [cautionNote] is shown in a stamp-red band at the top of the card when [caution] is set.
     */
    fun showTarget(
        target: Rect,
        title: String,
        body: String?,
        footer: String?,
        caution: Boolean,
        step: Int? = null,
        total: Int? = null,
        mark: String? = null,
        cautionNote: String? = null,
    ) {
        view.target = Rect(target)
        view.caution = caution
        view.setCard(title, body, footer, step, total, mark, cautionNote.takeIf { caution })
        reveal()
    }

    /** Card only, no ring: "not sure", errors. */
    fun showMessage(title: String, body: String? = null) {
        view.target = null
        view.caution = false
        view.setCard(title, body, null, null, null, null, null)
        reveal()
    }

    /** Follow a target that moved (the user scrolled) without redoing the model call. */
    fun moveTarget(target: Rect) {
        if (!isShowingTarget || view.target == target) return
        view.target = Rect(target)
        view.invalidate()
    }

    fun hide() {
        view.target = null
        if (attached) view.visibility = View.GONE
    }

    private fun reveal() {
        attach()
        view.visibility = View.VISIBLE
        view.invalidate()
    }

    private class HighlightView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val sp = resources.displayMetrics.scaledDensity

        var target: Rect? = null
        var caution = false

        private var title: String = ""
        private var body: String? = null
        private var footer: String? = null
        private var step: Int? = null
        private var total: Int? = null
        private var mark: String? = null
        private var cautionNote: String? = null
        private var titleLayout: StaticLayout? = null
        private var bodyLayout: StaticLayout? = null
        private var footerLayout: StaticLayout? = null
        private var cautionLayout: StaticLayout? = null
        private var layoutWidth = 0

        private val dim = Paint().apply { color = 0x8C0D1322.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = INK; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND
        }
        private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; textSize = 24f * sp; typeface = Typeface.DEFAULT_BOLD
        }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 19f * sp }
        private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_SOFT; textSize = 15f * sp; typeface = Typeface.DEFAULT_BOLD
        }

        private val cautionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = STAMP; textSize = 16f * sp; typeface = Typeface.DEFAULT_BOLD
        }
        private val bandFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STAMP_BG }
        private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val origin = IntArray(2)
        private val hole = RectF()
        private val path = Path()
        private val cardRect = RectF()

        fun setCard(
            title: String,
            body: String?,
            footer: String?,
            step: Int?,
            total: Int?,
            mark: String?,
            cautionNote: String?,
        ) {
            this.title = title
            this.body = body?.takeIf { it.isNotBlank() && it != title }
            this.footer = footer?.takeIf { it.isNotBlank() }
            this.step = step
            this.total = total?.takeIf { step != null && it in 2..MAX_SEGMENTS && step in 1..it }
            this.mark = mark?.trim()?.takeIf { it.length >= 2 }
            this.cautionNote = cautionNote?.takeIf { it.isNotBlank() }
            layoutWidth = 0 // rebuild text layouts on next draw
            invalidate()
        }

        private fun buildLayouts(width: Int) {
            if (width == layoutWidth || width <= 0) return
            layoutWidth = width
            fun make(text: CharSequence, paint: TextPaint, maxLines: Int, w: Int = width) =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.12f)
                    .setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            titleLayout = make(markTitle(), titlePaint, 3)
            bodyLayout = body?.let { make(it, bodyPaint, 5) }
            footerLayout = footer?.let { make(it, footerPaint, 1) }
            cautionLayout = cautionNote?.let { make(it, cautionPaint, 3, width - (2 * BAND_PAD_DP * d).toInt()) }
        }

        /**
         * The title with a highlighter background behind the button name: the target's label if
         * the title quotes it, otherwise the first «...» quote. Plain text when neither is found.
         */
        private fun markTitle(): CharSequence {
            val m = mark
            var start = -1
            var end = -1
            if (m != null) {
                start = title.indexOf(m, ignoreCase = true)
                if (start >= 0) end = start + m.length
            }
            if (start < 0) {
                val open = title.indexOf('«')
                val close = if (open >= 0) title.indexOf('»', open + 1) else -1
                if (open >= 0 && close > open + 1) { start = open + 1; end = close }
            }
            if (start < 0) return title
            return android.text.SpannableString(title).apply {
                setSpan(android.text.style.BackgroundColorSpan(HIGHLIGHT), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        override fun onDraw(canvas: Canvas) {
            // getBoundsInScreen is in absolute screen pixels; convert to this view's coordinates.
            getLocationOnScreen(origin)
            val accent = if (caution) CAUTION else HIGHLIGHT
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

                // Highlighter ring over a dark edge (ink, or stamp red on caution) so it reads on
                // white web pages and on dark apps alike.
                halo.color = if (caution) STAMP else INK
                halo.strokeWidth = 10f * d
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
            val bandPad = BAND_PAD_DP * d
            val segH = 6f * d
            val segs = total
            var contentH = tl.height.toFloat()
            cautionLayout?.let { contentH += it.height + 2 * bandPad + gap * 2 }
            footerLayout?.let { contentH += it.height + gap }
            if (segs != null) contentH += segH + gap * 2
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
            cardEdge.color = if (caution) STAMP else INK
            canvas.drawRoundRect(cardRect, r, r, cardEdge)

            canvas.save()
            canvas.translate(cardRect.left + padH, cardRect.top + padV)
            val innerW = cardRect.width() - 2 * padH
            cautionLayout?.let {
                // Stamp-red band: this step sends, pays or deletes. Chỉ Đây still never taps.
                val bandH = it.height + 2 * bandPad
                canvas.drawRoundRect(0f, 0f, innerW, bandH, 12f * d, 12f * d, bandFill)
                canvas.save()
                canvas.translate(bandPad, bandPad)
                it.draw(canvas)
                canvas.restore()
                canvas.translate(0f, bandH + gap * 2)
            }
            footerLayout?.let {
                it.draw(canvas)
                canvas.translate(0f, it.height + gap)
            }
            if (segs != null) {
                // Progress: one segment per step, done and current filled.
                val cur = step ?: 0
                val sGap = 5f * d
                val segW = (innerW - sGap * (segs - 1)) / segs
                for (k in 0 until segs) {
                    segPaint.color = if (k < cur) (if (caution) STAMP else INK) else LINE
                    val x = k * (segW + sGap)
                    canvas.drawRoundRect(x, 0f, x + segW, segH, segH / 2, segH / 2, segPaint)
                }
                canvas.translate(0f, segH + gap * 2)
            }
            tl.draw(canvas)
            canvas.translate(0f, tl.height + gap)
            bodyLayout?.draw(canvas)
            canvas.restore()
        }

        private companion object {
            const val HIGHLIGHT = 0xFFFFD43B.toInt()
            const val CAUTION = 0xFFFF8A8A.toInt()
            const val STAMP = 0xFFC8102E.toInt()
            const val STAMP_BG = 0xFFFDECEE.toInt()
            const val LINE = 0xFFDDE2EA.toInt()
            const val MAX_SEGMENTS = 12
            const val BAND_PAD_DP = 10f
            const val INK = 0xFF16223B.toInt()
            const val INK_SOFT = 0xFF5D6880.toInt()
            const val PULSE_MS = 1400L
            const val SMALL_DP = 72f
        }
    }
}
