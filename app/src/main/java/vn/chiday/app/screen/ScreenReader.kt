package vn.chiday.app.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One on-screen element as sent to the model. Schema from MECHANISM.md §2.1. */
@Serializable
data class UiNode(
    val i: Int,
    val role: String,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val id: String? = null,
    val clickable: Boolean? = null,
    /** Input fields only: whether the user has typed something. The typed text itself is never read. */
    val filled: Boolean? = null,
    val bounds: List<Int>,
)

@Serializable
data class ScreenPayload(
    @SerialName("package") val pkg: String,
    val host: String? = null,
    val nodes: List<UiNode>,
)

/** A read of the current screen. [rects] is parallel to [nodes] and is what the ring is drawn from. */
class ScreenSnapshot(
    val pkg: String,
    val host: String?,
    val nodes: List<UiNode>,
    val rects: List<Rect>,
    val screen: Rect,
    /** Bounds as the tree reports them, not clipped to the screen: tells a cut-off element from a whole one. */
    val fullRects: List<Rect> = rects,
    /** Input fields and secret nodes on screen; painted black in any screenshot. */
    val redactRects: List<Rect> = emptyList(),
) {
    /** Layout-independent fingerprint used to skip re-analysing an unchanged screen. */
    val hash: Int by lazy {
        var h = pkg.hashCode()
        for (n in nodes) {
            h = 31 * h + n.role.hashCode()
            h = 31 * h + (n.text?.hashCode() ?: 0)
            h = 31 * h + (n.desc?.hashCode() ?: 0)
            h = 31 * h + (n.id?.hashCode() ?: 0)
            h = 31 * h + (n.filled?.hashCode() ?: 0)
        }
        h
    }

    /**
     * The payload sent to the model. A long list is cut to [MAX_SENT] nodes, tap targets and
     * input fields first; every node keeps its own "i", so the model's index still maps to
     * [nodes]`[i]`.
     */
    fun toJson(): String {
        val sent = if (nodes.size <= MAX_SENT) nodes else {
            val keep = HashSet<Int>()
            nodes.filter { it.clickable == true || it.role == "edit" }.take(MAX_SENT).forEach { keep += it.i }
            for (n in nodes) {
                if (keep.size >= MAX_SENT) break
                keep += n.i
            }
            nodes.filter { it.i in keep }
        }
        return JSON.encodeToString(ScreenPayload(pkg, host, sent))
    }

    private companion object {
        const val MAX_SENT = 120
        val JSON = Json { explicitNulls = false; encodeDefaults = false }
    }
}

/**
 * Walks the accessibility tree of the app the user is looking at and produces the compact
 * node list. Read-only: no performAction anywhere.
 */
object ScreenReader {
    private const val MAX_NODES = 150
    private const val MAX_VISITED = 4000
    private const val MAX_TEXT = 80

    /** Icon-font glyphs (private-use area) and zero-width characters that sites put in labels. */
    private val JUNK = Regex("[\\uE000-\\uF8FF\\u200B-\\u200F\\uFEFF]")
    private val BROWSER_URL_BAR_IDS = listOf(":id/url_bar", ":id/location_bar_edit_text", ":id/mozac_browser_toolbar_url_view")

    fun read(service: AccessibilityService): ScreenSnapshot? {
        val screen = service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val root = findAppRoot(service) ?: return null
        val pkg = root.packageName?.toString() ?: return null

        val walker = Walker(screen)
        walker.visit(root, absorbed = false, ancestorLabel = null)
        // Browsers hide their address bar while the page scrolls. Keep the last host read from
        // the bar for that browser until the bar is visible again.
        val read = walker.host
        val last = lastBrowserHost
        val host = when {
            read != null -> read.also { lastBrowserHost = pkg to it }
            !walker.sawUrlBar && last != null && last.first == pkg -> last.second
            else -> null
        }
        return ScreenSnapshot(pkg, host, walker.nodes, walker.rects, Rect(screen), walker.fullRects, walker.redact)
    }

    /** (browser package, host) from the last read that showed the browser's address bar. */
    private var lastBrowserHost: Pair<String, String>? = null

    /**
     * The window the user is working in. Tapping our bubble can make the bubble's own window
     * "active", so rootInActiveWindow alone is not reliable: pick an application window that is
     * not ours, preferring the focused one.
     */
    private fun findAppRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val own = service.packageName
        val appWindows = runCatching { service.windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val ordered = appWindows.filter { it.isFocused } + appWindows.filter { it.isActive } + appWindows
        for (w in ordered) {
            val r = w.root ?: continue
            if (r.packageName?.toString() != own) return r
        }
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() != own }?.let { return it }
        // Nothing usable: say why in the log, this is the first thing to look at on a new phone.
        val all = runCatching { service.windows }.getOrDefault(emptyList())
        Log.w(
            "ChiDay",
            "no app root. windows=" + all.joinToString { w ->
                "type=${w.type} focused=${w.isFocused} active=${w.isActive} root=${w.root?.packageName}"
            },
        )
        return null
    }

    private class Walker(private val screen: Rect) {
        val nodes = ArrayList<UiNode>()
        val rects = ArrayList<Rect>()
        val fullRects = ArrayList<Rect>()
        val redact = ArrayList<Rect>()
        var host: String? = null
        var sawUrlBar = false
        private var visited = 0

        fun visit(node: AccessibilityNodeInfo, absorbed: Boolean, ancestorLabel: String?, inWeb: Boolean = false) {
            if (nodes.size >= MAX_NODES || visited++ >= MAX_VISITED) return
            if (!node.isVisibleToUser) {
                // Chrome marks web containers "not visible" once their top is scrolled off screen
                // (the page root, then e.g. Wikipedia's main column), while content inside them is
                // on screen. Chrome clips an off-screen node's bounds to the nearest screen edge,
                // so walk into hidden containers clipped to the top edge and skip those clipped to
                // the bottom edge (page content below the screen).
                if (!inWeb || node.childCount == 0) return
                val hr = Rect()
                node.getBoundsInScreen(hr)
                if (hr.top >= screen.centerY()) return
                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    visit(child, absorbed, ancestorLabel, inWeb = true)
                }
                return
            }

            val r = Rect()
            node.getBoundsInScreen(r)
            val onScreen = !r.isEmpty && Rect.intersects(r, screen)

            val id = node.viewIdResourceName
            val editable = node.isEditable
            val clickable = node.isClickable || node.isCheckable
            val secret = PrivacyFilter.isSecret(node)
            if (onScreen && PrivacyFilter.shouldRedact(node)) redact += Rect(r).apply { intersect(screen) }
            // Privacy: never read what the user typed into an input field, nor anything in a
            // password/PIN/OTP field. Only "filled or empty" and the field's label are kept.
            var text = if (editable || secret) null else clean(node.text)
            val filled = if (editable) PrivacyFilter.isFilled(node) else null
            // Web links with no text expose their href as the description: useless to the model.
            val desc = if (secret) null else clean(node.contentDescription)?.takeIf { !it.startsWith("javascript:") }
            val hint = clean(node.hintText)?.takeIf { it != text }

            if (id != null && host == null && BROWSER_URL_BAR_IDS.any { id.endsWith(it) }) {
                sawUrlBar = true
                // Only the site's host name is kept from the address bar, never the full URL.
                host = hostOf(node.text?.toString())
            }

            // A clickable container with no label of its own takes the label of its plain-text
            // children, so the ring lands on the real tap target and the list stays short.
            var absorbChildren = false
            if (clickable && !editable && text == null && desc == null) {
                val labels = ArrayList<String>(4)
                if (collectLabels(node, labels, depth = 0) && labels.isNotEmpty()) {
                    text = labels.joinToString(" · ").take(MAX_TEXT)
                    absorbChildren = true
                }
            }

            val label = text ?: desc ?: hint
            // Plain text is kept only as a leaf (containers repeat their children's text) and
            // only when it does not just repeat the label of the tap target it sits inside.
            val plainTextWorthKeeping = label != null && !absorbed && node.childCount == 0 &&
                (ancestorLabel == null || !ancestorLabel.contains(label.removeSuffix("…")))
            val keep = onScreen && (clickable || editable || plainTextWorthKeeping)
            if (keep) {
                // Native views report bounds already clipped by their scrolling parent, so a row
                // half under the screen edge looks like a short, whole row. Its real height is
                // in getBoundsInParent: grow the rect back on the side that is cut. Web content
                // is left alone (Chrome reports page bounds unclipped).
                val full = Rect(r)
                if (!inWeb) {
                    val own = Rect()
                    @Suppress("DEPRECATION")
                    node.getBoundsInParent(own)
                    if (own.height() > r.height() + 2) {
                        if (r.centerY() >= screen.centerY()) full.bottom = full.top + own.height() else full.top = full.bottom - own.height()
                    }
                }
                fullRects += full
                r.intersect(screen)
                nodes += UiNode(
                    i = nodes.size,
                    role = roleOf(node, editable, clickable),
                    text = text,
                    desc = desc.takeIf { it != text },
                    hint = hint,
                    id = id?.substringAfter(":id/")?.takeIf { it.isNotBlank() },
                    clickable = if (clickable) true else null,
                    filled = filled,
                    bounds = listOf(r.left, r.top, r.right, r.bottom),
                )
                rects += Rect(r)
            }

            for (c in 0 until node.childCount) {
                val child = node.getChild(c) ?: continue
                visit(
                    child,
                    absorbed = absorbChildren || (absorbed && !clickable),
                    ancestorLabel = if (clickable && label != null) label else ancestorLabel,
                    inWeb = inWeb || node.className == "android.webkit.WebView",
                )
            }
        }

        /**
         * Gathers labels of non-interactive descendants. Returns false when the subtree is too
         * big or contains its own tap targets, in which case nothing is absorbed.
         */
        private fun collectLabels(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int): Boolean {
            if (depth > 4) return false
            for (c in 0 until node.childCount) {
                val child = node.getChild(c) ?: continue
                if (!child.isVisibleToUser) continue
                if (child.isClickable || child.isEditable || child.isCheckable) return false
                if (PrivacyFilter.isSecret(child)) return false
                val label = clean(child.text) ?: clean(child.contentDescription)
                if (label != null) {
                    if (out.size >= 4) return false
                    out += label
                }
                if (!collectLabels(child, out, depth + 1)) return false
            }
            return true
        }
    }

    private fun roleOf(node: AccessibilityNodeInfo, editable: Boolean, clickable: Boolean): String {
        val cls = node.className?.toString().orEmpty()
        val webRole = node.extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()?.lowercase()
        return when {
            editable || cls.endsWith("EditText") -> "edit"
            cls.endsWith("Switch") || cls.endsWith("SwitchCompat") -> "switch"
            cls.endsWith("CheckBox") -> "checkbox"
            cls.endsWith("RadioButton") -> "radio"
            webRole == "link" || webRole == "liên kết" -> "link"
            cls.endsWith("Button") || webRole == "button" -> "button"
            cls.endsWith("Spinner") -> "dropdown"
            cls.endsWith("ImageView") || cls.endsWith("Image") -> if (clickable) "button" else "image"
            webRole != null && webRole.startsWith("heading") -> "heading"
            clickable -> "item"
            else -> "text"
        }
    }

    /** Trims, collapses whitespace, and masks personal numbers and e-mails (see [PrivacyFilter]). */
    internal fun clean(cs: CharSequence?): String? {
        if (cs.isNullOrBlank()) return null
        val s = cs.toString().replace(JUNK, "").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        val masked = PrivacyFilter.maskText(s)
        return if (masked.length > MAX_TEXT) masked.take(MAX_TEXT - 1) + "…" else masked
    }

    private fun hostOf(urlBarText: String?): String? {
        val t = urlBarText?.trim()?.takeIf { it.isNotEmpty() && !it.contains(' ') } ?: return null
        return t.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").lowercase()
            .takeIf { it.contains('.') }
    }
}
