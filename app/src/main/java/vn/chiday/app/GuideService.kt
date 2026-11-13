package vn.chiday.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.serialization.json.Json
import vn.chiday.app.overlay.BubbleWindow
import vn.chiday.app.screen.ScreenReader
import vn.chiday.app.screen.UiNode

/**
 * The core of the app. One accessibility permission gives us the overlay windows and the
 * screen tree. Guide-only: this class must never call performAction or dispatchGesture.
 */
class GuideService : AccessibilityService() {

    private var bubble: BubbleWindow? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        bubble = BubbleWindow(this, ::onBubbleTap).also { it.show() }
        Log.i(TAG, "service connected, bubble shown")
    }

    private fun onBubbleTap() {
        val snap = ScreenReader.read(this)
        if (snap == null) {
            Log.w(TAG, "bubble tapped: no readable app window")
            return
        }
        Log.i(TAG, "screen package=${snap.pkg} host=${snap.host} nodes=${snap.nodes.size} hash=${snap.hash}")
        if (BuildConfig.DEBUG) {
            // One node per line: logcat truncates long lines.
            val json = Json { explicitNulls = false }
            snap.nodes.forEach { Log.d(TAG, "node " + json.encodeToString(UiNode.serializer(), it)) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Follow mode (milestone 7) hooks in here.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        bubble?.hide()
        bubble = null
        if (instance === this) instance = null
    }

    companion object {
        const val TAG = "ChiDay"

        @Volatile
        var instance: GuideService? = null
            private set

        /** True when the system has actually bound the service in this process. */
        fun isRunning(): Boolean = instance != null

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
