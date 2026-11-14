package vn.chiday.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vn.chiday.app.R
import vn.chiday.app.playbook.Playbook
import vn.chiday.app.playbook.PlaybookDrafter
import vn.chiday.app.playbook.PlaybookStore
import vn.chiday.app.settings.SettingsStore

/**
 * Paste a written guide, get a draft playbook, review it, save it. Saved drafts show up as a
 * goal on Home and are used by the guide with a "chưa kiểm chứng" label.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlaybookImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val store = remember { PlaybookStore(context) }

    var service by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Playbook?>(null) }
    var savedTick by remember { mutableIntStateOf(0) }
    val mine = remember(savedTick) { store.userPlaybookIds().let { ids -> store.all().filter { it.id in ids } } }

    val needKey = stringResource(R.string.guide_no_key_body)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.import_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = service, onValueChange = { service = it }, singleLine = true,
            label = { Text(stringResource(R.string.import_service)) },
            modifier = Modifier.fillMaxWidth().testTag("import_service"),
        )
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true,
            label = { Text(stringResource(R.string.import_host)) },
            placeholder = { Text("dichvucong.gov.vn") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
            modifier = Modifier.fillMaxWidth().testTag("import_host"),
        )
        OutlinedTextField(
            value = goal, onValueChange = { goal = it }, singleLine = true,
            label = { Text(stringResource(R.string.import_goal)) },
            modifier = Modifier.fillMaxWidth().testTag("import_goal"),
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text(stringResource(R.string.import_text)) },
            minLines = 5, maxLines = 12,
            modifier = Modifier.fillMaxWidth().testTag("import_text"),
        )

        Button(
            onClick = {
                busy = true
                error = null
                draft = null
                scope.launch {
                    try {
                        val s = settings.current()
                        if (!s.isComplete) error = needKey else draft = PlaybookDrafter.draft(s, service, host, goal, text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && text.isNotBlank() && goal.isNotBlank() && PlaybookDrafter.cleanHost(host).contains('.'),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("btn_draft"),
            shape = RoundedCornerShape(18.dp),
        ) { Text(stringResource(if (busy) R.string.import_working else R.string.import_make)) }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("import_error"))
        }

        draft?.let { pb ->
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(16.dp).testTag("import_draft"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.import_draft_title, pb.steps.size), style = MaterialTheme.typography.titleMedium)
                pb.steps.forEach { step ->
                    Text("${step.n}. ${step.action}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.import_step_target, step.targetHint.orEmpty()) + if (step.caution) " ⚠" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.import_review_note), style = MaterialTheme.typography.bodyMedium, color = Warn)
                Button(
                    onClick = {
                        store.save(pb)
                        draft = null
                        savedTick++
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("btn_save_playbook"),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.import_save)) }
            }
        }

        if (mine.isNotEmpty()) {
            Text(stringResource(R.string.import_mine), style = MaterialTheme.typography.titleMedium)
            mine.forEach { pb ->
                OutlinedButton(
                    onClick = {
                        store.delete(pb.id)
                        savedTick++
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.import_delete, pb.goal), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
