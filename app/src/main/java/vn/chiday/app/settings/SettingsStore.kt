package vn.chiday.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Which wire format to speak. The host's provider is unknown until demo day. */
enum class Provider(val id: String, val defaultBaseUrl: String, val defaultModel: String) {
    /** OpenAI and any gateway that exposes POST {baseUrl}/chat/completions. */
    OPENAI("openai", "https://api.openai.com/v1", ""),

    /** Gemini through its OpenAI-compatible endpoint; same client as [OPENAI]. */
    GEMINI("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", ""),

    /** POST {baseUrl}/v1/messages. */
    ANTHROPIC("anthropic", "https://api.anthropic.com", "claude-opus-5-5");

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

data class LlmSettings(
    val provider: Provider = Provider.OPENAI,
    val baseUrl: String = Provider.OPENAI.defaultBaseUrl,
    val model: String = "",
    val apiKey: String = "",
    /** True when "Kiểm tra kết nối" last succeeded with exactly these values. */
    val verified: Boolean = false,
) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Settings live in DataStore so they survive kill-and-reopen. The key never leaves the phone. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    val flow: Flow<LlmSettings> = store.data.map { p ->
        val provider = Provider.fromId(p[PROVIDER])
        LlmSettings(
            provider = provider,
            baseUrl = p[BASE_URL] ?: provider.defaultBaseUrl,
            model = p[MODEL] ?: provider.defaultModel,
            apiKey = p[API_KEY] ?: "",
            verified = p[VERIFIED] ?: false,
        )
    }

    suspend fun current(): LlmSettings = flow.first()

    suspend fun save(settings: LlmSettings) {
        store.edit { p ->
            p[PROVIDER] = settings.provider.id
            p[BASE_URL] = settings.baseUrl.trim()
            p[MODEL] = settings.model.trim()
            p[API_KEY] = settings.apiKey.trim()
            p[VERIFIED] = settings.verified
        }
    }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val API_KEY = stringPreferencesKey("api_key")
        val VERIFIED = booleanPreferencesKey("verified")
    }
}
