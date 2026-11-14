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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
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

    init {
        view.keyboardTopProvider = { imeTop() }
    }

    /** Top of the soft keyboard in screen pixels, or null when no keyboard is showing. */
    private fun imeTop(): Int? {
        val r = Rect()
        for (w in service.windows) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                w.getBoundsInScreen(r)
                if (!r.isEmpty) return r.top
            }
        }
        return null
    }

    /**
     * Where the instruction card is (absolute screen pixels) while one is shown, so the bubble
     * can stay off it: pass it as the second argument of BubbleWindow.avoid.
     */
    val cardBoundsOnScreen: Rect?
        get() = if (isShowing) view.cardOnScreen() else null

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
        look: Boolean = false,
    ) {
        view.target = Rect(target)
        view.moveDir = 0
        view.caution = caution
        view.look = look
        view.setCard(title, body, footer, step, total, mark, cautionNote.takeIf { caution })
        reveal()
    }

    /**
     * No ring: the user has to move the screen first. Chevrons point to where the content is,
     * down when [below] (more further down the page), up otherwise. The screen is not dimmed.
     */
    fun showMove(below: Boolean, title: String, body: String?, footer: String?) {
        view.target = null
        view.look = false
        view.moveDir = if (below) 1 else -1
        view.caution = false
        view.setCard(title, body, footer, null, null, null, null)
        reveal()
    }

    /** Card only, no ring: "not sure", errors. */
    fun showMessage(title: String, body: String? = null) {
        view.target = null
        view.look = false
        view.moveDir = 0
        view.caution = false
        view.setCard(title, body, null, null, null, null, null)
        reveal()
    }

    /** Follow a target that moved (the user scrolled) without redoing the model call. */
    fun moveTarget(target: Rect) {
        if (!isShowingTarget || view.target == target) return
        view.target = Rect(target)
        view.refreshKeyboard()
        view.invalidate()
    }

    fun hide() {
        view.target = null
        view.moveDir = 0
        if (attached) view.visibility = View.GONE
    }

    private fun reveal() {
        attach()
        view.refreshKeyboard()
        view.visibility = View.VISIBLE
        view.invalidate()
    }

    private class HighlightView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val sp = resources.displayMetrics.scaledDensity

        var target: Rect? = null
        var caution = false

        /** The target is text to read, not a button: dashed frame, no pulse, no arrow. */
        var look = false

        /** 0 = none, 1 = "more below" chevrons at the bottom, -1 = "more above" at the top. */
        var moveDir = 0

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

        /** Cache key of the text layouts: width and size variant (line limits). */
        private var layoutKey = -1L
        private var textVersion = 0

        /** Supplies the soft keyboard's top (screen px), or null; polled at most every 500 ms. */
        var keyboardTopProvider: (() -> Int?)? = null
        private var kbTopScreen: Int? = null
        private var kbCheckedAt = 0L

        // Card placement, cached between frames (onDraw runs every frame for the pulse).
        private val placedRect = RectF()
        private var placedVariant = 0
        private var placedValid = false
        private val pkTarget = Rect()
        private var pkHasTarget = false
        private var pkMoveDir = 0
        private var pkW = 0
        private var pkH = 0
        private var pkOx = 0
        private var pkOy = 0
        private var pkKb = 0
        private var pkInsTop = 0
        private var pkInsBottom = 0
        private var pkIme = 0
        private var pkVersion = -1

        private val cardMargin = 16f * d
        private val padH = 20f * d
        private val padV = 16f * d
        private val gap = 6f * d
        private val segH = 6f * d

        private val dim = Paint().apply { color = 0x8C0D1322.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dash = android.graphics.DashPathEffect(floatArrayOf(18f * d, 12f * d), 0f)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = INK; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND
        }
        private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
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
            layoutKey = -1L // rebuild text layouts on next draw
            textVersion++
            invalidate()
        }

        fun refreshKeyboard() {
            kbCheckedAt = SystemClock.uptimeMillis()
            kbTopScreen = runCatching { keyboardTopProvider?.invoke() }.getOrNull()
        }

        private fun innerWidth(): Int = (width - 2 * cardMargin - 2 * padH).toInt()

        /** Builds the text layouts for [width] at size variant [v] (see TITLE_LINES/BODY_LINES). */
        private fun buildLayouts(width: Int, v: Int) {
            if (width <= 0) return
            val key = width.toLong() * 16 + v
            if (key == layoutKey) return
            layoutKey = key
            val compact = v == COMPACT
            fun make(text: CharSequence, paint: TextPaint, maxLines: Int, w: Int = width) =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, w.coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.12f)
                    .setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            titleLayout = make(markTitle(), titlePaint, TITLE_LINES[v])
            bodyLayout = if (BODY_LINES[v] > 0) body?.let { make(it, bodyPaint, BODY_LINES[v]) } else null
            footerLayout = if (compact) null else footer?.let { make(it, footerPaint, 1) }
            cautionLayout = cautionNote?.let {
                make(it, cautionPaint, if (compact) 1 else 3, width - (2 * BAND_PAD_DP * d).toInt())
            }
        }

        private fun showSegs(v: Int) = v != COMPACT && total != null

        /** Card height at size variant [v]; leaves that variant's layouts built. */
        private fun cardHeight(v: Int): Float {
            buildLayouts(innerWidth(), v)
            val tl = titleLayout ?: return 0f
            val bandPad = BAND_PAD_DP * d
            var contentH = tl.height.toFloat()
            cautionLayout?.let { contentH += it.height + 2 * bandPad + gap * 2 }
            footerLayout?.let { contentH += it.height + gap }
            if (showSegs(v)) contentH += segH + gap * 2
            bodyLayout?.let { contentH += it.height + gap }
            return contentH + 2 * padV
        }

        /** Card rectangle in absolute screen pixels, or null when there is no card. */
        fun cardOnScreen(): Rect? {
            if (!ensurePlacement()) return null
            return Rect(
                (placedRect.left + origin[0]).toInt(), (placedRect.top + origin[1]).toInt(),
                (placedRect.right + origin[0]).toInt(), (placedRect.bottom + origin[1]).toInt(),
            )
        }

        /** Recomputes the card placement only when an input to it changed. */
        private fun ensurePlacement(): Boolean {
            if (title.isEmpty() || width <= 0 || height <= 0) return false
            getLocationOnScreen(origin)
            val t = target
            val kb = kbTopScreen ?: Int.MAX_VALUE
            val wi = rootWindowInsets
            val ins = wi?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val insTop = ins?.top ?: -1
            val insBottom = ins?.bottom ?: -1
            val ime = wi?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            val sameTarget = if (t == null) !pkHasTarget else pkHasTarget && pkTarget == t
            if (placedValid && sameTarget && pkVersion == textVersion && pkMoveDir == moveDir &&
                pkW == width && pkH == height && pkOx == origin[0] && pkOy == origin[1] && pkKb == kb &&
                pkInsTop == insTop && pkInsBottom == insBottom && pkIme == ime
            ) return true
            pkHasTarget = t != null
            if (t != null) pkTarget.set(t)
            pkVersion = textVersion
            pkMoveDir = moveDir
            pkW = width
            pkH = height
            pkOx = origin[0]
            pkOy = origin[1]
            pkKb = kb
            pkInsTop = insTop
            pkInsBottom = insBottom
            pkIme = ime
            place(t, kb, insTop, insBottom, ime)
            placedValid = true
            return true
        }

        /**
         * Puts the card in the free band above or below the keep-out area (target ring + pulse +
         * arrow, or the chevrons), inside the system bars and above the keyboard. Prefers the
         * band farther from the target; shrinks the card before overlapping anything.
         */
        private fun place(t: Rect?, kb: Int, insTop: Int, insBottom: Int, ime: Int) {
            val safeTop = if (insTop >= 0) max(insTop.toFloat(), 24f * d) + 12f * d else 56f * d
            var safeBottom = if (insBottom >= 0) height - max(insBottom.toFloat(), 16f * d) - 16f * d else height - 72f * d
            if (ime > 0) safeBottom = min(safeBottom, height - ime - 8f * d)
            if (kb != Int.MAX_VALUE) safeBottom = min(safeBottom, (kb - origin[1]).toFloat() - 8f * d)

            val keep = keepOut(t)
            val aboveTop = safeTop
            val aboveBottom: Float
            val belowTop: Float
            val belowBottom = safeBottom
            val preferBelow: Boolean
            if (keep == null) {
                aboveBottom = safeTop // no band above: the card goes at the bottom as before
                belowTop = safeTop
                preferBelow = true
            } else {
                aboveBottom = min(keep.top, safeBottom)
                belowTop = max(keep.bottom, safeTop)
                preferBelow = if (t != null) (t.centerY() - origin[1]) < height * 0.55f else moveDir < 0
            }
            val aboveH = aboveBottom - aboveTop
            val belowH = belowBottom - belowTop

            for (v in TITLE_LINES.indices) {
                val h = cardHeight(v)
                for (k in 0..1) {
                    val below = if (k == 0) preferBelow else !preferBelow
                    if (below && h <= belowH) { setPlaced(v, belowBottom - h, h); return }
                    if (!below && h <= aboveH) { setPlaced(v, aboveTop, h); return }
                }
            }
            // Nothing fits (huge target or keyboard): compact card in the larger band.
            val h = cardHeight(COMPACT)
            val top = if (belowH >= aboveH) belowBottom - h else aboveTop
            setPlaced(COMPACT, top.coerceIn(0f, max(0f, height - h)), h)
        }

        private fun setPlaced(v: Int, top: Float, h: Float) {
            placedVariant = v
            placedRect.set(cardMargin, top, width - cardMargin, top + h)
        }

        /** The area the card must not cover, in view coordinates; null when nothing is pointed at. */
        private fun keepOut(t: Rect?): RectF? {
            if (t != null) {
                val pad = 8f * d
                val k = RectF(t.left - origin[0] - pad, t.top - origin[1] - pad, t.right - origin[0] + pad, t.bottom - origin[1] + pad)
                val small = min(k.width(), k.height()) < SMALL_DP * d && max(k.width(), k.height()) < 3 * SMALL_DP * d
                val cx = k.centerX()
                val cy = k.centerY()
                val tip = max(k.width(), k.height()) / 2f + 14f * d
                k.inset(-KEEP_DP * d, -KEEP_DP * d)
                if (small) {
                    // Same geometry as drawArrow, at its farthest bob.
                    val a = arrowAngle(cx, cy)
                    val ux = cos(a)
                    val uy = sin(a)
                    val tail = tip + 10f * d + 64f * d
                    val box = RectF(
                        min(cx + ux * tip, cx + ux * tail), min(cy + uy * tip, cy + uy * tail),
                        max(cx + ux * tip, cx + ux * tail), max(cy + uy * tip, cy + uy * tail),
                    )
                    box.inset(-28f * d, -28f * d)
                    k.union(box)
                }
                return k
            }
            if (moveDir > 0) {
                val base = height - 250f * d
                return RectF(0f, base - 16f * d, width.toFloat(), base + 2 * 28f * d + 30f * d + 14f * d + 16f * d)
            }
            if (moveDir < 0) {
                val base = 150f * d
                return RectF(0f, base - 2 * 28f * d - 30f * d - 14f * d - 16f * d, width.toFloat(), base + 16f * d)
            }
            return null
        }

        private fun arrowAngle(cx: Float, cy: Float): Float {
            var ang = atan2(height / 2f - cy, width / 2f - cx)
            if (hypot(width / 2f - cx, height / 2f - cy) < 40f * d) ang = (Math.PI / 2).toFloat()
            return ang
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
                ring.pathEffect = if (look) dash else null
                canvas.drawRoundRect(hole, radius, radius, ring)
                ring.pathEffect = null

                // Pulse: a second ring that grows and fades.
                val phase = (SystemClock.uptimeMillis() % PULSE_MS) / PULSE_MS.toFloat()
                val grow = phase * 22f * d
                ring.alpha = if (look) 0 else ((1f - phase) * 200).toInt()
                ring.strokeWidth = 4f * d
                hole.inset(-grow, -grow)
                canvas.drawRoundRect(hole, radius + grow, radius + grow, ring)
                hole.inset(grow, grow)

                if (!look && min(hole.width(), hole.height()) < SMALL_DP * d && max(hole.width(), hole.height()) < 3 * SMALL_DP * d) drawArrow(canvas, accent, phase)
            }

            if (t == null && moveDir != 0) drawMoveHint(canvas, accent)
            // Keyboard position: polled at most every KB_REFRESH_MS, never per frame.
            if (SystemClock.uptimeMillis() - kbCheckedAt >= KB_REFRESH_MS) refreshKeyboard()
            drawCard(canvas)
            if (t != null || moveDir != 0) postInvalidateOnAnimation()
            else if (title.isNotEmpty()) postInvalidateDelayed(KB_REFRESH_MS) // card only: follow the keyboard
        }

        /** Three bobbing chevrons at the edge the content is beyond. */
        private fun drawMoveHint(canvas: Canvas, accent: Int) {
            val phase = (SystemClock.uptimeMillis() % PULSE_MS) / PULSE_MS.toFloat()
            val bob = (sin(phase * 2 * Math.PI).toFloat() + 1f) * 7f * d * moveDir
            val cx = width / 2f
            val half = 46f * d
            val drop = 30f * d * moveDir
            val gap = 28f * d * moveDir
            val base = if (moveDir > 0) height - 250f * d else 150f * d
            path.reset()
            for (k in 0..2) {
                val y = base + k * gap + bob
                path.moveTo(cx - half, y)
                path.lineTo(cx, y + drop)
                path.lineTo(cx + half, y)
            }
            chevron.color = INK // dark edge so the yellow chevron reads on white pages
            chevron.strokeWidth = 15f * d
            canvas.drawPath(path, chevron)
            chevron.color = accent
            chevron.strokeWidth = 9f * d
            canvas.drawPath(path, chevron)
        }

        /** An arrow pointing at a small target from the direction of the screen centre. */
        private fun drawArrow(canvas: Canvas, accent: Int, phase: Float) {
            val cx = hole.centerX()
            val cy = hole.centerY()
            val ang = arrowAngle(cx, cy)
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

        private fun drawCard(canvas: Canvas) {
            // Placement is cached; it is recomputed only when target, text, size or keyboard change.
            if (!ensurePlacement()) return
            buildLayouts(innerWidth(), placedVariant)
            val tl = titleLayout ?: return
            val bandPad = BAND_PAD_DP * d
            val segs = if (showSegs(placedVariant)) total else null
            cardRect.set(placedRect)

            val r = 22f * d
            canvas.drawRoundRect(cardRect, r, r, cardFill)
            cardEdge.color = if (caution) STAMP else INK
            canvas.drawRoundRect(cardRect, r, r, cardEdge)

            canvas.save()
            canvas.clipRect(cardRect) // text never spills outside the card
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

            /** Keep-out margin around the ring: pulse growth (22) + ring stroke + air. */
            const val KEEP_DP = 34f
            const val KB_REFRESH_MS = 500L

            /** Card size variants, largest first: title and body line limits. */
            val TITLE_LINES = intArrayOf(3, 3, 3, 3, 2, 1)
            val BODY_LINES = intArrayOf(5, 3, 1, 0, 0, 0)
            const val COMPACT = 5
        }
    }
}
