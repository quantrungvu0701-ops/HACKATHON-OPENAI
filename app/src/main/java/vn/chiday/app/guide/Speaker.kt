package vn.chiday.app.guide

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks instructions with the phone's own text-to-speech engine in Vietnamese. If the
 * Vietnamese voice is missing nothing is spoken: the subtitle card still shows the text and
 * Home shows how to install the voice.
 */
class Speaker(context: Context) {

    @Volatile
    private var ready = false

    @Volatile
    var vietnameseAvailable = false
        private set

    private var pending: String? = null
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        vietnameseAvailable = isVietnameseAvailable(tts)
        if (vietnameseAvailable) {
            tts.language = VIETNAMESE
            tts.setSpeechRate(0.92f) // a little slower than default for older listeners
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "speech started $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "speech done $utteranceId")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "speech error $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "speech error $utteranceId code=$errorCode")
            }
        })
        ready = true
        Log.i(TAG, "TTS ready, engine=${tts.defaultEngine}, vietnamese=$vietnameseAvailable")
        pending?.let { speak(it) }
        pending = null
    }

    /** Replaces whatever is being said. Returns false when it will not be spoken. */
    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        if (!ready) {
            pending = text // engine still binding (right after a kill-and-reopen)
            return true
        }
        if (!vietnameseAvailable) {
            Log.w(TAG, "no Vietnamese voice: subtitle only")
            return false
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chiday-${++counter}")
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        pending = null
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        ready = false
    }

    companion object {
        private const val TAG = "ChiDay"
        val VIETNAMESE: Locale = Locale("vi", "VN")

        fun isVietnameseAvailable(tts: TextToSpeech): Boolean =
            runCatching { tts.isLanguageAvailable(VIETNAMESE) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
    }
}
