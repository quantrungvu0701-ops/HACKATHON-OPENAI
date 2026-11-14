package vn.chiday.app.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import vn.chiday.app.settings.LlmSettings
import vn.chiday.app.settings.Provider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One model call. The phone talks to the provider directly; there is no backend. */
interface LlmClient {
    suspend fun complete(system: String, user: String, imageJpeg: ByteArray? = null): String
}

/** [httpCode] is null for network failures. [message] is safe to show on the Settings screen. */
class LlmException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

object Llm {
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        // A pooled connection can die silently while idle (NAT, mobile networks). HTTP/2 pings
        // find that out in seconds instead of waiting for the read timeout.
        .pingInterval(4, TimeUnit.SECONDS)
        // Keep the warmed connection longer than OkHttp's 5-minute default: a user may read a
        // page for a while between steps, and a new TLS handshake adds hundreds of ms.
        .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
        .build()

    fun clientFor(settings: LlmSettings): LlmClient = when (settings.provider) {
        Provider.ANTHROPIC -> AnthropicClient(settings.baseUrl, settings.model, settings.apiKey)
        Provider.OPENAI, Provider.GEMINI -> OpenAiCompatClient(settings.baseUrl, settings.model, settings.apiKey)
    }

    /**
     * Opens (and keeps in the pool) a connection to the model server, so the first guide call
     * after the app is reopened does not pay for DNS and the TLS handshake. Errors are ignored.
     */
    fun warmUp(baseUrl: String) {
        val request = runCatching { Request.Builder().url(baseUrl.trimEnd('/') + "/models").head().build() }.getOrNull() ?: return
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    /** Runs the request, cancels the socket if the coroutine is cancelled, returns the body of a 2xx. */
    suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(LlmException("Không kết nối được: ${e.message ?: e.javaClass.simpleName}", cause = e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = try {
                        r.body?.string().orEmpty()
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(LlmException("Mất kết nối khi đang nhận trả lời: ${e.message}", cause = e))
                        return
                    }
                    if (!cont.isActive) return
                    if (r.isSuccessful) {
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(LlmException("HTTP ${r.code}: ${body.take(300)}", httpCode = r.code))
                    }
                }
            }
        })
    }
}
