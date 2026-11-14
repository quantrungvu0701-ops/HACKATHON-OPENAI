package vn.chiday.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import vn.chiday.app.ui.ChiDayTheme
import vn.chiday.app.ui.DocCheckScreen
import vn.chiday.app.ui.HomeScreen
import vn.chiday.app.ui.PlaybookImportScreen
import vn.chiday.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    /** Bumped on every onResume so Home re-reads service state after kill-and-reopen. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChiDayTheme {
                var screen by rememberSaveable { mutableStateOf(SCREEN_HOME) }
                when (screen) {
                    SCREEN_SETTINGS -> {
                        BackHandler { screen = SCREEN_HOME }
                        SettingsScreen(onBack = { screen = SCREEN_HOME })
                    }

                    SCREEN_DOCCHECK -> {
                        BackHandler { screen = SCREEN_HOME }
                        DocCheckScreen(onBack = { screen = SCREEN_HOME })
                    }

                    SCREEN_IMPORT -> {
                        BackHandler { screen = SCREEN_HOME }
                        PlaybookImportScreen(onBack = { screen = SCREEN_HOME })
                    }

                    else -> HomeScreen(
                        resumeTick = resumeTick,
                        onOpenAccessibility = ::openAccessibilitySettings,
                        onOpenAppInfo = ::openAppInfo,
                        onOpenSettings = { screen = SCREEN_SETTINGS },
                        onOpenVoiceSettings = ::openVoiceSettings,
                        onOpenDocCheck = { screen = SCREEN_DOCCHECK },
                        onOpenImport = { screen = SCREEN_IMPORT },
                        onStart = {
                            // "Bắt đầu" always brings the bubble back if it was switched off.
                            GuideService.setBubbleOn(this, true)
                            moveTaskToBack(true)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Voice-data install screen of the TTS engine, falling back to the system TTS settings. */
    private fun openVoiceSettings() {
        val candidates = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        for (intent in candidates) {
            val ok = runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val SCREEN_HOME = "home"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_DOCCHECK = "doccheck"
        const val SCREEN_IMPORT = "import"
    }
}
