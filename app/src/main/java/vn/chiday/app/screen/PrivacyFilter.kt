package vn.chiday.app.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Everything that leaves the accessibility tree or the screen goes through here first.
 *
 * Rules (same idea as iOS hiding passwords in screen recordings):
 *  1. What the user types is never read. Input fields are sent as "edit, filled or empty" plus
 *     their label/hint; their text is dropped at the source, not after the fact.
 *  2. Password fields and fields whose label says PIN/OTP/CVV/card/password are treated the same,
 *     and their descriptions are dropped too.
 *  3. Personal numbers and e-mail addresses shown anywhere on screen are masked: digit runs of 6+
 *     (phone, CCCD/ID, account, card, OTP, also when split by spaces, dots or dashes) and e-mails.
 *  4. Screenshots are redacted in memory: every input field and sensitive node is painted solid
 *     black before the image is encoded. The unredacted bitmap is recycled right away and is
 *     never written to disk, logged, or sent. Windows marked FLAG_SECURE (banking apps, password
 *     screens) cannot be captured by Android at all, and we do not try to work around that.
 */
object PrivacyFilter {
    const val MASK = "••••••"

    /** 6+ digits, possibly split by single spaces, dots or dashes ("0912 345 678", "079-203-001-234"). */
    private val LONG_NUMBER = Regex("\\d(?:[ .\\-]?\\d){5,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    /** Labels that mark a field as secret even when the app forgot to set isPassword. */
    private val SECRET_LABEL = Regex(
        "(mật\\s*khẩu|mat\\s*khau|password|passcode|\\bpin\\b|mã\\s*pin|\\botp\\b|mã\\s*xác\\s*(thực|nhận)|cvv|cvc|" +
            "số\\s*thẻ|card\\s*number|tài\\s*khoản\\s*ngân\\s*hàng|số\\s*tài\\s*khoản|account\\s*number|secret)",
        RegexOption.IGNORE_CASE,
    )

    /** Masks personal numbers and e-mail addresses in text that is shown on screen. */
    fun maskText(s: String): String = EMAIL.replace(LONG_NUMBER.replace(s) { MASK }, "[email]")

    /** True for nodes whose content must never be read: passwords and secret-looking fields. */
    fun isSecret(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val label = listOfNotNull(node.hintText, node.contentDescription, node.viewIdResourceName, node.paneTitle)
            .joinToString(" ")
        return label.isNotEmpty() && SECRET_LABEL.containsMatchIn(label)
    }

    /** True for nodes whose pixels must be blacked out in a screenshot. */
    fun shouldRedact(node: AccessibilityNodeInfo): Boolean = node.isEditable || isSecret(node)

    /** Whether an input field holds something the user typed (as opposed to empty or showing its hint). */
    fun isFilled(node: AccessibilityNodeInfo): Boolean {
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (node.isShowingHintText) return false
        return t != node.hintText?.toString()?.trim()
    }

    /**
     * Paints every rect in [redact] black on a copy of [source], encodes it as JPEG, and recycles
     * both bitmaps. Only the redacted bytes leave this function.
     */
    fun redactToJpeg(source: Bitmap, redact: List<Rect>, quality: Int = 80): ByteArray {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        source.recycle()
        try {
            val canvas = Canvas(copy)
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            val pad = 4
            for (r in redact) {
                canvas.drawRect(
                    (r.left - pad).toFloat(), (r.top - pad).toFloat(),
                    (r.right + pad).toFloat(), (r.bottom + pad).toFloat(), paint,
                )
            }
            val out = ByteArrayOutputStream()
            copy.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        } finally {
            copy.recycle()
        }
    }

    /**
     * The only screenshot path in the app. Takes a screenshot of the default display, redacts the
     * input fields and secret nodes from [snapshot], and returns JPEG bytes held in memory only.
     * Returns null when Android refuses (secure window, rate limit) — callers then use the node
     * list alone. The overlay must be hidden by the caller before this is called.
     */
    suspend fun captureRedacted(service: AccessibilityService, snapshot: ScreenSnapshot): ByteArray? {
        val raw = suspendCancellableCoroutine<Bitmap?> { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = result.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        hw.close()
                        cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) = cont.resume(null)
                },
            )
        } ?: return null
        return redactToJpeg(raw, snapshot.redactRects)
    }
}
