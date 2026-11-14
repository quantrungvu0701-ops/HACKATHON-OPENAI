package vn.chiday.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vn.chiday.app.R
import vn.chiday.app.doc.DocChecker
import vn.chiday.app.doc.DocVerdict
import vn.chiday.app.doc.PreparedPhoto
import vn.chiday.app.guide.Speaker
import vn.chiday.app.settings.SettingsStore
import java.io.File

private sealed interface DocState {
    data object Empty : DocState
    data object Working : DocState
    data class Done(val verdict: DocVerdict, val millis: Long) : DocState
    data class Failed(val reason: String) : DocState
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DocCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    val kinds = listOf(
        stringResource(R.string.doc_kind_id),
        stringResource(R.string.doc_kind_portrait),
        stringResource(R.string.doc_kind_other),
    )
    var kind by rememberSaveable { mutableStateOf(0) }
    var photo by remember { mutableStateOf<PreparedPhoto?>(null) }
    var state by remember { mutableStateOf<DocState>(DocState.Empty) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val noKey = stringResource(R.string.guide_no_key_body)
    val okSpoken = stringResource(R.string.doc_ok_title)
    val badSpoken = stringResource(R.string.doc_bad_title)

    fun run(uri: Uri) {
        state = DocState.Working
        speaker.stop()
        scope.launch {
            state = try {
                val settings = store.current()
                if (!settings.isComplete) {
                    DocState.Failed(noKey)
                } else {
                    val prepared = DocChecker.prepare(context, uri)
                    photo = prepared
                    val started = android.os.SystemClock.elapsedRealtime()
                    val verdict = DocChecker.check(settings, prepared, kinds[kind])
                    // Read the verdict aloud as well: the audience is not confident readers.
                    speaker.speak(
                        listOfNotNull(if (verdict.ok) okSpoken else badSpoken, verdict.problems.joinToString(" ").ifBlank { null }, verdict.advice)
                            .joinToString(". "),
                    )
                    DocState.Done(verdict, android.os.SystemClock.elapsedRealtime() - started)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DocState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) run(uri) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) run(uri)
    }

    val working = state == DocState.Working
    val verdict = (state as? DocState.Done)?.verdict

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack, contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Text(stringResource(R.string.settings_back), color = Ink2, style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.doc_title), style = MaterialTheme.typography.headlineLarge, color = Ink)
        if (photo == null) {
            Text(stringResource(R.string.doc_intro), style = MaterialTheme.typography.bodyLarge, color = InkSoft)
        }

        Text(stringResource(R.string.doc_kind_label), style = MaterialTheme.typography.bodyMedium, color = InkSoft, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, label ->
                val selected = i == kind
                OutlinedButton(
                    onClick = { kind = i },
                    enabled = !working,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("doc_kind_$i"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    border = BorderStroke(2.dp, if (selected) Ink else Line),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Ink else Card,
                        contentColor = if (selected) Color.White else Ink,
                    ),
                ) { Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            }
        }

        photo?.let { p ->
            Image(
                bitmap = p.preview.asImageBitmap(),
                contentDescription = stringResource(R.string.doc_photo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, if (verdict == null) Line else if (verdict.ok) Ok else Stamp, RoundedCornerShape(16.dp)),
            )
        }

        when (val s = state) {
            DocState.Working -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("doc_working"),
            ) {
                CircularProgressIndicator(color = Ink, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.doc_working), style = MaterialTheme.typography.titleMedium, color = Ink)
            }
            is DocState.Done -> VerdictCard(s.verdict, s.millis)
            is DocState.Failed -> AccentCard(accent = Stamp, tag = "doc_failed") {
                Text(stringResource(R.string.settings_test_failed), style = MaterialTheme.typography.titleMedium, color = Stamp)
                Text(s.reason, style = MaterialTheme.typography.bodyLarge, color = Ink)
            }
            DocState.Empty -> {}
        }

        Spacer(Modifier.height(4.dp))

        // Same place and look as «Bắt đầu» on Home, so the main action is always where the eye expects it.
        Button(
            onClick = {
                // The camera app writes the full-size photo to our cache through a FileProvider
                // URI, so the app itself needs no camera or storage permission.
                val file = File(File(context.cacheDir, "doccheck").apply { mkdirs() }, "photo.jpg")
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                cameraUri = uri
                runCatching { takePicture.launch(uri) }.onFailure { state = DocState.Failed(it.message ?: "camera") }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("btn_camera"),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink,
                contentColor = Color.White,
                disabledContainerColor = Line,
                disabledContentColor = InkSoft,
            ),
        ) {
            Text(
                stringResource(if (verdict != null && !verdict.ok) R.string.doc_retake else R.string.doc_take),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Text(
            stringResource(R.string.doc_take_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("btn_gallery"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, Line),
        ) { Text(stringResource(R.string.doc_pick), color = Ink) }
    }
}

/** White card with a coloured bar on the left, the same shape as the goal match card on Home. */
@Composable
private fun AccentCard(accent: Color, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .testTag(tag),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun VerdictCard(v: DocVerdict, millis: Long) {
    val tint = if (v.ok) Ok else Stamp
    AccentCard(accent = tint, tag = if (v.ok) "doc_ok" else "doc_bad") {
        Text(
            stringResource(if (v.ok) R.string.doc_ok_title else R.string.doc_bad_title),
            style = MaterialTheme.typography.titleLarge,
            color = tint,
        )
        v.document?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.doc_detected, it), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        }
        v.problems.filter { it.isNotBlank() }.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = Ink) }
        v.advice?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Ink, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.doc_time, millis), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
    }
}
