package vn.chiday.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chiday.app.GuideService
import vn.chiday.app.R
import vn.chiday.app.settings.SettingsStore

enum class CheckState { OK, WARN, MISSING }

@Composable
fun HomeScreen(
    resumeTick: Int,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val settings by remember { SettingsStore(context).flow }.collectAsState(initial = null)
    val aiState = when {
        settings?.isComplete != true -> CheckState.MISSING
        settings?.verified == true -> CheckState.OK
        else -> CheckState.WARN
    }
    // Re-read on every resume: after a kill-and-reopen the process is new and state must be fresh.
    val enabled = remember(resumeTick) { GuideService.isEnabledInSettings(context) }
    val running by GuideService.running.collectAsState()
    val serviceState = when {
        enabled && running -> CheckState.OK
        enabled -> CheckState.WARN
        else -> CheckState.MISSING
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = Brand)
        Text(
            stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.home_checklist_title), style = MaterialTheme.typography.titleMedium)

        CheckRow(
            state = serviceState,
            title = stringResource(R.string.check_service_title),
            detail = stringResource(
                when (serviceState) {
                    CheckState.OK -> R.string.check_service_ok
                    CheckState.WARN -> R.string.check_service_starting
                    CheckState.MISSING -> R.string.check_service_off
                },
            ),
            onClick = if (serviceState == CheckState.OK) null else onOpenAccessibility,
        )

        CheckRow(
            state = aiState,
            title = stringResource(R.string.check_ai_title),
            detail = stringResource(
                when (aiState) {
                    CheckState.OK -> R.string.check_ai_ok
                    CheckState.WARN -> R.string.check_ai_untested
                    CheckState.MISSING -> R.string.check_ai_missing
                },
            ),
            onClick = onOpenSettings,
        )

        if (serviceState != CheckState.OK) {
            Button(
                onClick = onOpenAccessibility,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(stringResource(R.string.home_open_accessibility)) }

            RestrictedSettingsHelp(onOpenAppInfo)
        }

        Spacer(Modifier.height(4.dp))

        // A key that is saved but untested still lets the user start; a missing key does not.
        val ready = serviceState == CheckState.OK && aiState != CheckState.MISSING
        Button(
            onClick = onStart,
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            shape = RoundedCornerShape(20.dp),
        ) { Text(stringResource(R.string.home_start), fontSize = 24.sp, fontWeight = FontWeight.Bold) }

        Text(
            stringResource(
                when {
                    serviceState != CheckState.OK -> R.string.home_not_ready
                    aiState == CheckState.MISSING -> R.string.home_not_ready_ai
                    else -> R.string.home_start_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(R.string.home_open_settings)) }
    }
}

@Composable
fun CheckRow(state: CheckState, title: String, detail: String, onClick: (() -> Unit)?) {
    val tint = when (state) {
        CheckState.OK -> Ok
        CheckState.WARN -> Warn
        CheckState.MISSING -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (state) {
                    CheckState.OK -> "✓"
                    CheckState.WARN -> "!"
                    CheckState.MISSING -> "✕"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.restricted_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.restricted_step_1), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.restricted_step_2), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.restricted_step_3), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.restricted_step_4), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = onOpenAppInfo,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(R.string.home_open_app_info)) }
    }
}
