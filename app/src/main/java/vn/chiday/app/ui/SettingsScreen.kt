package vn.chiday.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vn.chiday.app.R
import vn.chiday.app.llm.Llm
import vn.chiday.app.llm.LlmException
import vn.chiday.app.settings.LlmSettings
import vn.chiday.app.settings.Provider
import vn.chiday.app.settings.SettingsStore

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val reply: String, val millis: Long) : TestState
    data class Failed(val reason: String) : TestState
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var loaded by rememberSaveable { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf(Provider.OPENAI) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val s = store.current()
            provider = s.provider
            baseUrl = s.baseUrl
            model = s.model
            apiKey = s.apiKey
            loaded = true
        }
    }

    fun draft(verified: Boolean) = LlmSettings(provider, baseUrl.trim(), model.trim(), apiKey.trim(), verified)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Provider.entries.forEach { p ->
                val selected = p == provider
                val label = stringResource(
                    when (p) {
                        Provider.OPENAI -> R.string.provider_openai
                        Provider.GEMINI -> R.string.provider_gemini
                        Provider.ANTHROPIC -> R.string.provider_anthropic
                    },
                )
                val onPick = {
                    if (!selected) {
                        // Switching provider swaps in that provider's default address; the key is kept.
                        provider = p
                        baseUrl = p.defaultBaseUrl
                        model = p.defaultModel
                        test = TestState.Idle
                    }
                }
                val mod = Modifier.weight(1f).heightIn(min = 56.dp).testTag("provider_${p.id}")
                if (selected) {
                    Button(onClick = onPick, modifier = mod, shape = RoundedCornerShape(14.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedButton(onClick = onPick, modifier = mod, shape = RoundedCornerShape(14.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; test = TestState.Idle },
            label = { Text(stringResource(R.string.settings_base_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
            modifier = Modifier.fillMaxWidth().testTag("field_base_url"),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; test = TestState.Idle },
            label = { Text(stringResource(R.string.settings_model)) },
            placeholder = { Text(stringResource(R.string.settings_model_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
            modifier = Modifier.fillMaxWidth().testTag("field_model"),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; test = TestState.Idle },
            label = { Text(stringResource(R.string.settings_key)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(stringResource(if (showKey) R.string.settings_hide else R.string.settings_show))
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("field_key"),
        )

        Button(
            onClick = {
                val candidate = draft(verified = false)
                test = TestState.Running
                scope.launch {
                    // Save first: a key that was typed must survive even if the test call fails.
                    store.save(candidate)
                    test = try {
                        val started = SystemClock.elapsedRealtime()
                        val reply = Llm.clientFor(candidate).complete(
                            system = "You are a connection test for a Vietnamese app. Reply with one short friendly sentence in Vietnamese.",
                            user = "Xin chào, bạn có nghe rõ không?",
                        )
                        store.save(candidate.copy(verified = true))
                        TestState.Ok(reply.trim(), SystemClock.elapsedRealtime() - started)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: LlmException) {
                        TestState.Failed(e.message ?: "?")
                    } catch (e: Exception) {
                        TestState.Failed(e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            enabled = loaded && draft(false).isComplete && test != TestState.Running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("btn_test"),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(stringResource(if (test == TestState.Running) R.string.settings_testing else R.string.settings_test))
        }

        when (val t = test) {
            is TestState.Ok -> ResultCard(
                title = stringResource(R.string.settings_test_ok, t.millis),
                body = t.reply,
                good = true,
            )
            is TestState.Failed -> ResultCard(
                title = stringResource(R.string.settings_test_failed),
                body = t.reason,
                good = false,
            )
            else -> {}
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    val previous = store.current()
                    val candidate = draft(verified = false)
                    // Keep the green check only if nothing that the test covered has changed.
                    store.save(candidate.copy(verified = previous.verified && previous.copy(verified = false) == candidate))
                    onBack()
                }
            },
            enabled = loaded,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("btn_save"),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(R.string.settings_save)) }

        Text(
            stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(title: String, body: String, good: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (good) Ok.copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
            .padding(16.dp)
            .testTag(if (good) "test_ok" else "test_failed"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (good) Ok else MaterialTheme.colorScheme.error)
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}
