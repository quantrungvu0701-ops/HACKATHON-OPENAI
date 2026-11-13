package vn.chiday.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import vn.chiday.app.guide.GuideEngine
import vn.chiday.app.guide.GuideResult
import vn.chiday.app.overlay.BubbleWindow
import vn.chiday.app.overlay.HighlightWindow
import vn.chiday.app.screen.ScreenReader
import vn.chiday.app.screen.ScreenSnapshot
import vn.chiday.app.screen.UiNode
import vn.chiday.app.settings.SettingsStore
import kotlin.math.abs

/**
 * The core of the app. One accessibility permission gives us the overlay windows and the
 * screen tree. Guide-only: this class must never call performAction or dispatchGesture.
 */
class GuideService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: SettingsStore
    private lateinit var engine: GuideEngine
    private var bubble: BubbleWindow? = null
    private var highlight: HighlightWindow? = null

    /** The model call in flight. One at a time; a new tap cancels the old one. */
    private var analysis: Job? = null
    private var settle: Job? = null
    private var messageTimeout: Job? = null

    /** The node currently ringed, so the ring can follow a scroll or clear when the screen moves on. */
    private var shown: UiNode? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = SettingsStore(this)
        engine = GuideEngine(settings)
        highlight = HighlightWindow(this)
        bubble = BubbleWindow(this, ::onBubbleTap).also { it.show() }
        _running.value = true
        Log.i(TAG, "service connected, bubble shown")
    }

    /** Idle: analyse this screen. Thinking: cancel. Ring showing: end the session. */
    private fun onBubbleTap() {
        when {
            analysis?.isActive == true -> {
                Log.i(TAG, "tap while thinking: cancelled")
                endSession()
            }

            highlight?.isShowingTarget == true -> {
                Log.i(TAG, "tap while guiding: session ended")
                endSession()
            }

            else -> analyse()
        }
    }

    private fun analyse() {
        clearOverlay()
        // The overlay is hidden before the tree is read, and our own windows are skipped anyway.
        val snap = ScreenReader.read(this)
        if (snap == null) {
            Log.w(TAG, "no readable app window")
            showMessage(getString(R.string.guide_no_screen), null)
            return
        }
        logSnapshot(snap)
        bubble?.state = BubbleWindow.State.THINKING
        analysis = scope.launch {
            engine.goal = settings.currentGoal()
            val started = android.os.SystemClock.elapsedRealtime()
            val result = engine.decide(snap)
            Log.i(TAG, "decision in ${android.os.SystemClock.elapsedRealtime() - started} ms: $result")
            when (result) {
                is GuideResult.Show -> show(result, snap)
                is GuideResult.NotSure -> showMessage(getString(R.string.guide_not_sure), null)
                is GuideResult.Failed -> if (result.missingKey) {
                    showMessage(getString(R.string.guide_no_key), getString(R.string.guide_no_key_body))
                } else {
                    showMessage(getString(R.string.guide_failed), getString(R.string.guide_failed_body))
                }
            }
        }
    }

    private fun show(result: GuideResult.Show, snap: ScreenSnapshot) {
        val reply = result.reply
        val say = reply.say?.trim().orEmpty()
        val title = reply.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: say
        val step = reply.step
        val total = reply.totalSteps
        val footer = if (step != null && total != null && step in 1..total) getString(R.string.guide_step, step, total) else null
        val body = listOfNotNull(
            say.takeIf { it.isNotEmpty() && it != title },
            getString(R.string.guide_caution).takeIf { reply.caution },
        ).joinToString("\n").takeIf { it.isNotEmpty() }

        shown = snap.nodes[result.targetIndex]
        highlight?.showTarget(result.rect, title, body, footer, reply.caution)
        bubble?.bringToFront()
        bubble?.state = BubbleWindow.State.ACTIVE
        Log.i(TAG, "ring on node ${result.targetIndex} at ${result.rect.toShortString()} caution=${reply.caution}")
    }

    private fun showMessage(title: String, body: String?) {
        shown = null
        highlight?.showMessage(title, body)
        bubble?.bringToFront()
        bubble?.state = BubbleWindow.State.IDLE
        Log.i(TAG, "message: $title")
        messageTimeout?.cancel()
        messageTimeout = scope.launch {
            delay(MESSAGE_MS)
            if (highlight?.isShowingTarget == false) highlight?.hide()
        }
    }

    private fun clearOverlay() {
        analysis?.cancel()
        settle?.cancel()
        messageTimeout?.cancel()
        shown = null
        highlight?.hide()
        bubble?.state = BubbleWindow.State.IDLE
    }

    private fun endSession() {
        clearOverlay()
        engine.resetSession()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only watched while a ring is up; nothing is read outside a session.
        if (shown == null || event == null) return
        if (event.packageName == packageName) return
        settle?.cancel()
        settle = scope.launch {
            delay(SETTLE_MS)
            recheckTarget()
        }
    }

    /**
     * The screen changed while a ring was showing. If the same element is still there (the
     * user scrolled) the ring follows it; if it is gone (the user moved on) the ring is cleared
     * so it never points at the wrong thing. Milestone 7 replaces "clear" with "analyse again".
     */
    private fun recheckTarget() {
        val was = shown ?: return
        val snap = ScreenReader.read(this)
        val oldTop = was.bounds[1]
        val match = snap?.nodes?.withIndex()
            ?.filter { (_, n) -> n.role == was.role && n.text == was.text && n.desc == was.desc && n.id == was.id }
            ?.minByOrNull { (_, n) -> abs(n.bounds[1] - oldTop) }
        if (snap == null || match == null) {
            Log.i(TAG, "target left the screen: ring cleared")
            clearOverlay()
            return
        }
        val rect: Rect = snap.rects[match.index]
        shown = match.value
        highlight?.moveTarget(rect)
    }

    private fun logSnapshot(snap: ScreenSnapshot) {
        Log.i(TAG, "screen package=${snap.pkg} host=${snap.host} nodes=${snap.nodes.size} hash=${snap.hash}")
        if (BuildConfig.DEBUG) {
            // One node per line: logcat truncates long lines.
            snap.nodes.forEach { Log.d(TAG, "node " + NODE_JSON.encodeToString(UiNode.serializer(), it)) }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        analysis?.cancel()
        settle?.cancel()
        messageTimeout?.cancel()
        shown = null
        highlight?.hide()
        highlight = null
        bubble?.hide()
        bubble = null
        if (instance === this) {
            instance = null
            _running.value = false
        }
    }

    companion object {
        const val TAG = "ChiDay"
        private const val SETTLE_MS = 800L
        private const val MESSAGE_MS = 7000L
        private val NODE_JSON = Json { explicitNulls = false }

        @Volatile
        var instance: GuideService? = null
            private set

        private val _running = MutableStateFlow(false)

        /**
         * True while the system has the service bound in this process. A flow, not a one-off
         * read: after a kill-and-reopen the activity can be on screen before the system rebinds.
         */
        val running: StateFlow<Boolean> = _running

        /** True when the user has switched the service on in Settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, GuideService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
