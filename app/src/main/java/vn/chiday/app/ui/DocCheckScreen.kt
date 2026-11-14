package vn.chiday.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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

/** Deletes the camera's full-size photo from our cache. Safe to call when there is none. */
private fun deleteCameraPhoto(context: android.content.Context) {
    File(context.cacheDir, "doccheck").deleteRecursively()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DocCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) {
        // A photo left behind by a crash or a cancelled check is removed on entry and exit.
        deleteCameraPhoto(context)
        onDispose { speaker.shutdown(); deleteCameraPhoto(context) }
    }

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
                    // Privacy: the full-size camera photo of an ID card must not stay on the
                    // phone. Only the scaled copy in memory is kept, for this screen only.
                    deleteCameraPhoto(context)
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
        if (saved && uri != null) run(uri) else deleteCameraPhoto(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(R.string.doc_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.doc_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, label ->
                val mod = Modifier.weight(1f).heightIn(min = 56.dp).testTag("doc_kind_$i")
                if (i == kind) {
                    Button(onClick = {}, modifier = mod, shape = RoundedCornerShape(14.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedButton(onClick = { kind = i }, modifier = mod, shape = RoundedCornerShape(14.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Button(
            onClick = {
                // The camera app writes the full-size photo to our cache through a FileProvider
                // URI, so the app itself needs no camera or storage permission.
                val file = File(File(context.cacheDir, "doccheck").apply { mkdirs() }, "photo.jpg")
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                cameraUri = uri
                runCatching { takePicture.launch(uri) }.onFailure { state = DocState.Failed(it.message ?: "camera") }
            },
            enabled = state != DocState.Working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("btn_camera"),
            shape = RoundedCornerShape(18.dp),
        ) { Text(stringResource(R.string.doc_take)) }

        OutlinedButton(
            onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = state != DocState.Working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("btn_gallery"),
            shape = RoundedCornerShape(18.dp),
        ) { Text(stringResource(R.string.doc_pick)) }

        when (val s = state) {
            DocState.Working -> Text(stringResource(R.string.doc_working), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("doc_working"))
            is DocState.Done -> VerdictCard(s.verdict, s.millis)
            is DocState.Failed -> Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f)).padding(16.dp).testTag("doc_failed"),
            ) {
                Text(stringResource(R.string.settings_test_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(s.reason, style = MaterialTheme.typography.bodyLarge)
            }
            DocState.Empty -> {}
        }

        photo?.let { p ->
            Image(
                bitmap = p.preview.asImageBitmap(),
                contentDescription = stringResource(R.string.doc_photo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}

@Composable
private fun VerdictCard(v: DocVerdict, millis: Long) {
    val tint = if (v.ok) Ok else MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(16.dp)
            .testTag(if (v.ok) "doc_ok" else "doc_bad"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(if (v.ok) R.string.doc_ok_title else R.string.doc_bad_title),
            style = MaterialTheme.typography.titleLarge,
            color = tint,
        )
        v.document?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.doc_detected, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        v.problems.filter { it.isNotBlank() }.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
        v.advice?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(stringResource(R.string.doc_time, millis), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
