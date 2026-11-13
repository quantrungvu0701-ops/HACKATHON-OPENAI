package vn.chiday.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import vn.chiday.app.ui.ChiDayTheme
import vn.chiday.app.ui.HomeScreen

class MainActivity : ComponentActivity() {

    /** Bumped on every onResume so Home re-reads service state after kill-and-reopen. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChiDayTheme {
                HomeScreen(
                    resumeTick = resumeTick,
                    onOpenAccessibility = ::openAccessibilitySettings,
                    onOpenAppInfo = ::openAppInfo,
                    onStart = { moveTaskToBack(true) },
                )
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

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
