package vn.chiday.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import vn.chiday.app.R

/** What the mic is doing, for the goal field to draw. */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** [partial] is what the recogniser has heard so far; shown live in the field. */
    data class Listening(val partial: String = "") : VoiceState

    data class Failed(@StringRes val message: Int) : VoiceState
}

/**
 * Speech-to-text for the goal, in Vietnamese. Uses the in-app [SpeechRecognizer] so the field
 * can show "Đang nghe…" and the words as they are heard. If the phone has no recogniser, or
 * it cannot do Vietnamese, it falls back to the system speech dialog, which needs no
 * microphone permission. The text never leaves the phone through us: the recogniser is the
 * phone's own (usually Google's).
 */
@Stable
class VoiceInput internal constructor(
    private val context: Context,
    private val onText: (String) -> Unit,
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set

    /** Loudness 0..1 while listening, to animate the mic halo. */
    var level by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null
    internal var askPermission: () -> Unit = {}
    internal var openSystemDialog: () -> Boolean = { false }

    val listening get() = state is VoiceState.Listening

    /** Mic button: start, or stop and keep what was heard. */
    fun toggle() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            if (!openSystemDialog()) state = VoiceState.Failed(R.string.voice_unavailable)
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else askPermission()
    }

    internal fun onPermission(granted: Boolean) {
        if (granted) {
            start()
        } else if (!openSystemDialog()) {
            state = VoiceState.Failed(R.string.voice_no_permission)
        }
    }

    internal fun onSystemDialogResult(text: String?) {
        state = VoiceState.Idle
        if (!text.isNullOrBlank()) onText(text.trim())
    }

    private fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        state = VoiceState.Listening()
        level = 0f
        runCatching { r.startListening(intent()) }.onFailure {
            Log.w(TAG, "startListening failed", it)
            state = VoiceState.Failed(R.string.voice_failed)
        }
    }

    internal fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onEndOfSpeech() {
            level = 0f
        }

        // Typical range is about -2 dB (silence) to 10 dB (speech).
        override fun onRmsChanged(rmsdB: Float) {
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = partialResults?.firstResult() ?: return
            if (listening) state = VoiceState.Listening(heard)
        }

        override fun onResults(results: Bundle?) {
            val heard = results?.firstResult() ?: (state as? VoiceState.Listening)?.partial
            level = 0f
            if (heard.isNullOrBlank()) {
                state = VoiceState.Failed(R.string.voice_no_match)
            } else {
                state = VoiceState.Idle
                onText(heard.trim())
            }
        }

        override fun onError(error: Int) {
            level = 0f
            Log.i(TAG, "speech error $error")
            // Heard something before stopping: keep it rather than throwing it away.
            val partial = (state as? VoiceState.Listening)?.partial
            if (!partial.isNullOrBlank() &&
                (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)
            ) {
                state = VoiceState.Idle
                onText(partial.trim())
                return
            }
            state = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceState.Failed(R.string.voice_no_match)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceState.Failed(R.string.voice_network)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceState.Failed(R.string.voice_no_permission)
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                    if (openSystemDialog()) VoiceState.Idle else VoiceState.Failed(R.string.voice_unavailable)
                else -> VoiceState.Failed(R.string.voice_failed)
            }
        }
    }

    private fun Bundle.firstResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "ChiDay"
        private const val LANG = "vi-VN"

        // Added in API 31; written as numbers so minSdk 30 compiles and runs.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANG)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
}

/** Remembers a [VoiceInput] tied to this screen; [onText] gets the final words heard. */
@Composable
fun rememberVoiceInput(onText: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onText)
    val voice = remember { VoiceInput(context) { latest(it) } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        voice.onPermission(it)
    }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        voice.onSystemDialogResult(text)
    }
    voice.askPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    voice.openSystemDialog = {
        runCatching {
            dialog.launch(VoiceInput.intent().putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_prompt)))
        }.isSuccess
    }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}
