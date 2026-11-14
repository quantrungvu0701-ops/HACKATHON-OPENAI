package vn.chiday.app.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import vn.chiday.app.playbook.Playbook
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
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.chiday.app.BuildConfig
import vn.chiday.app.GuideService
import vn.chiday.app.R
import vn.chiday.app.guide.Speaker
import vn.chiday.app.playbook.PlaybookStore
import vn.chiday.app.settings.SettingsStore

enum class CheckState { OK, WARN, MISSING }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    resumeTick: Int,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onOpenDocCheck: () -> Unit,
    onOpenImport: () -> Unit,
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

    val voiceState = rememberVietnameseVoiceState(resumeTick)
    // Collapse the checklist to one green line only when nothing needs the user's attention.
    // A voice check still running (null) counts as fine, so Home does not flicker on resume.
    val allGood = serviceState == CheckState.OK && aiState == CheckState.OK && voiceState != CheckState.MISSING
    var showChecks by rememberSaveable { mutableStateOf(false) }
    val store = remember { SettingsStore(context) }
    var goal by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (goal == null) goal = store.currentGoal() }
    LaunchedEffect(goal) {
        val g = goal ?: return@LaunchedEffect
        delay(400)
        store.saveGoal(g)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (allGood && !showChecks) {
            ReadyLine(onDetails = { showChecks = true })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_checklist_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (allGood) {
                    TextButton(onClick = { showChecks = false }) {
                        Text(stringResource(R.string.home_ready_hide), color = Ink2)
                    }
                }
            }

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

            CheckRow(
                state = voiceState ?: CheckState.WARN,
                title = stringResource(R.string.check_voice_title),
                detail = stringResource(
                    when (voiceState) {
                        CheckState.OK -> R.string.check_voice_ok
                        null, CheckState.WARN -> R.string.check_voice_checking
                        CheckState.MISSING -> R.string.check_voice_missing
                    },
                ),
                onClick = if (voiceState == CheckState.MISSING) onOpenVoiceSettings else null,
            )

            if (serviceState != CheckState.OK) {
                Button(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.home_open_accessibility)) }

                RestrictedSettingsHelp(onOpenAppInfo)
            }
        }

        Text(
            stringResource(R.string.home_goal_label),
            style = MaterialTheme.typography.headlineLarge,
            color = Ink,
            modifier = Modifier.padding(top = 4.dp),
        )

        GoalSection(goal = goal.orEmpty(), onGoal = { goal = it })

        // A key that is saved but untested still lets the user start; a missing key does not.
        val ready = serviceState == CheckState.OK && aiState != CheckState.MISSING
        Button(
            onClick = onStart,
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("btn_start"),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink,
                contentColor = Color.White,
                disabledContainerColor = Line,
                disabledContentColor = InkSoft,
            ),
        ) {
            if (ready) {
                BubbleMark(Modifier.size(24.dp), disc = Color.White)
                Spacer(Modifier.width(12.dp))
            }
            Text(stringResource(R.string.home_start), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        // The way to take the bubble off the screen without switching the permission off.
        val bubbleOn by GuideService.bubbleOn.collectAsState()
        androidx.compose.runtime.LaunchedEffect(resumeTick) { GuideService.refreshBubbleSwitch(context) }
        if (serviceState == CheckState.OK) {
            androidx.compose.material3.OutlinedButton(
                onClick = { GuideService.setBubbleOn(context, !bubbleOn) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("btn_bubble_switch"),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    stringResource(if (bubbleOn) R.string.home_bubble_off else R.string.home_bubble_on),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }
            Text(
                stringResource(if (bubbleOn) R.string.home_bubble_on_hint else R.string.home_bubble_off_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            stringResource(
                when {
                    serviceState != CheckState.OK -> R.string.home_not_ready
                    aiState == CheckState.MISSING -> R.string.home_not_ready_ai
                    else -> R.string.home_start_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = onOpenDocCheck,
            enabled = aiState != CheckState.MISSING,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("btn_doccheck"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, Line),
        ) { Text(stringResource(R.string.home_open_doccheck), color = Ink) }

        OutlinedButton(
            onClick = onOpenImport,
            enabled = aiState != CheckState.MISSING,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("btn_import"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, Line),
        ) { Text(stringResource(R.string.home_open_import), color = Ink) }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, Line),
        ) { Text(stringResource(R.string.home_open_settings), color = Ink) }

        Text(
            stringResource(R.string.home_build, BuildConfig.GIT_SHA),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
    }
}

/** One green line when every check passes; the full checklist is one tap away. */
@Composable
private fun ReadyLine(onDetails: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(Ok),
            contentAlignment = Alignment.Center,
        ) { Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.home_ready),
            style = MaterialTheme.typography.titleMedium,
            color = Ok,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDetails, modifier = Modifier.testTag("btn_checks")) {
            Text(
                stringResource(R.string.home_ready_details),
                color = Ink2,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Asks the default text-to-speech engine whether it can speak Vietnamese. Null while the
 * engine is still binding. Re-checked on every resume, e.g. after installing voice data.
 */
@Composable
private fun rememberVietnameseVoiceState(resumeTick: Int): CheckState? {
    val context = LocalContext.current
    var state by remember { mutableStateOf<CheckState?>(null) }
    DisposableEffect(resumeTick) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            state = if (status == TextToSpeech.SUCCESS && engine != null && Speaker.isVietnameseAvailable(engine)) {
                CheckState.OK
            } else {
                CheckState.MISSING
            }
        }
        onDispose { runCatching { tts.shutdown() } }
    }
    return state
}

/**
 * The user's goal in their own words, typed or spoken, plus the checked guides they can pick.
 * Saved by the caller as it changes, so it survives kill-and-reopen.
 */
@Composable
private fun GoalSection(goal: String, onGoal: (String) -> Unit) {
    val context = LocalContext.current
    val voice = rememberVoiceInput(onText = onGoal)
    val listening = voice.state as? VoiceState.Listening
    val shape = RoundedCornerShape(18.dp)
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(shape)
            .background(Card)
            .border(2.dp, if (listening != null || focused) Ink else Line, shape)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (listening != null) {
                val heard = listening.partial
                Text(
                    heard.ifBlank { stringResource(R.string.voice_listening) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (heard.isBlank()) InkSoft else Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                BasicTextField(
                    value = goal,
                    onValueChange = onGoal,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Ink),
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .testTag("field_goal"),
                    decorationBox = { inner ->
                        if (goal.isEmpty()) {
                            Text(
                                stringResource(R.string.home_goal_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = InkSoft,
                            )
                        }
                        inner()
                    },
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        MicButton(voice)
    }

    when (val st = voice.state) {
        is VoiceState.Listening -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Stamp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.voice_listening_hint), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        }

        is VoiceState.Failed -> Text(
            stringResource(st.message),
            style = MaterialTheme.typography.bodyMedium,
            color = Stamp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        VoiceState.Idle -> Unit
    }

    val known = remember { PlaybookStore(context).all() }
    val trimmed = goal.trim()
    val match = remember(trimmed, known) { if (trimmed.isEmpty()) null else PlaybookStore.matchGoal(trimmed, known) }
    if (trimmed.isNotEmpty() && listening == null) MatchCard(match)

    // Tasks that have a checked step-by-step guide: one tap fills the goal in.
    if (known.isNotEmpty()) {
        Text(
            stringResource(R.string.home_goal_known),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
            fontWeight = FontWeight.SemiBold,
        )
        Column {
            HorizontalDivider(color = Line)
            known.forEach { pb ->
                val selected = match?.id == pb.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .background(if (selected) Highlight.copy(alpha = 0.35f) else Color.Transparent)
                        .clickable { onGoal(if (selected) "" else pb.goal) }
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("goal_${pb.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pb.goal, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(pb.service, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                    }
                    Text(if (selected) "✓" else "›", fontSize = 24.sp, color = if (selected) Ok else InkSoft, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}

/** Tells the user whether a checked guide covers what they asked for. */
@Composable
private fun MatchCard(pb: Playbook?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .testTag("match_card"),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(if (pb != null) Highlight else Line))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (pb != null) {
                Text(stringResource(R.string.match_found_title), style = MaterialTheme.typography.titleMedium, color = Ink)
                val host = pb.match.hosts.firstOrNull() ?: pb.service
                Text(
                    stringResource(R.string.match_found_body, pb.goal, pb.steps.size, host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft,
                )
                pb.verified?.date?.let(::shortDate)?.let {
                    Text(stringResource(R.string.match_found_checked, it), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                }
            } else {
                Text(stringResource(R.string.match_none), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
            }
        }
    }
}

/** "2026-11-14" to "14/11/2026"; anything else unchanged, blank to null. */
private fun shortDate(iso: String): String? {
    if (iso.isBlank()) return null
    val p = iso.split("-")
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
}

/**
 * The mic: ink disc with a yellow mic. While listening it shows a stop square and a yellow
 * halo that grows with the user's voice, so they can see they are being heard.
 */
@Composable
private fun MicButton(voice: VoiceInput) {
    val listening = voice.listening
    val level by animateFloatAsState(if (listening) voice.level else 0f, label = "mic-level")
    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val beat by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "mic-beat",
    )
    val label = stringResource(if (listening) R.string.voice_stop else R.string.voice_start)
    Box(
        modifier = Modifier
            .size(60.dp)
            .drawBehind {
                if (listening) {
                    val base = size.minDimension / 2f
                    drawCircle(Highlight.copy(alpha = 0.35f), radius = base * (1.15f + 0.1f * beat + 0.35f * level))
                    drawCircle(Highlight, radius = base * 1.08f)
                }
            }
            .clip(CircleShape)
            .background(Ink)
            .clickable { voice.toggle() }
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .testTag("btn_mic"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val w = size.width
            val h = size.height
            if (listening) {
                drawRoundRect(Highlight, topLeft = Offset(w * 0.22f, h * 0.22f), size = Size(w * 0.56f, h * 0.56f), cornerRadius = CornerRadius(w * 0.08f))
            } else {
                val stroke = w * 0.09f
                // Capsule, cradle, stem.
                drawRoundRect(Highlight, topLeft = Offset(w * 0.34f, 0f), size = Size(w * 0.32f, h * 0.62f), cornerRadius = CornerRadius(w * 0.16f))
                drawArc(
                    Highlight, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(w * 0.2f, h * 0.22f), size = Size(w * 0.6f, h * 0.56f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawLine(Highlight, Offset(w / 2f, h * 0.78f), Offset(w / 2f, h * 0.96f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

/** The floating bubble's mark (disc, yellow ring, yellow dot), so users recognise it outside the app. */
@Composable
private fun BubbleMark(modifier: Modifier, disc: Color = Ink) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(disc, radius = r)
        drawCircle(HighlightDeep, radius = r * 0.55f, style = Stroke(width = r * 0.22f))
        drawCircle(HighlightDeep, radius = r * 0.2f)
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
