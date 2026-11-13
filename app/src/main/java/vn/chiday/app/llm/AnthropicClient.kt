package vn.chiday.app.llm

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POST {baseUrl}/v1/messages (Claude Messages API), hand-built over OkHttp because the
 * project stack is OkHttp + kotlinx.serialization with a user-entered base URL.
 *
 * No `temperature`/`top_p`/`top_k` and no `thinking` field are sent: current Claude models
 * reject sampling parameters and manage thinking themselves. Thinking shares `max_tokens`
 * with the answer, so the limit is generous even though the answer is a few lines.
 */
class AnthropicClient(baseUrl: String, private val model: String, private val apiKey: String) : LlmClient {

    private val url = baseUrl.trim().trimEnd('/').removeSuffix("/messages").removeSuffix("/v1") + "/v1/messages"

    override suspend fun complete(system: String, user: String, imageJpeg: ByteArray?): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    if (imageJpeg != null) {
                                        add(
                                            buildJsonObject {
                                                put("type", "image")
                                                putJsonObject("source") {
                                                    put("type", "base64")
                                                    put("media_type", "image/jpeg")
                                                    put("data", Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
                                                }
                                            },
                                        )
                                    }
                                    add(buildJsonObject { put("type", "text"); put("text", user) })
                                },
                            )
                        },
                    )
                },
            )
        }

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .post(body.toString().toRequestBody(Llm.JSON_MEDIA))
            .build()

        return parse(Llm.execute(request))
    }

    private fun parse(raw: String): String {
        val root = runCatching { Llm.json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw LlmException("Máy chủ trả về dữ liệu không phải JSON: ${raw.take(200)}") }

        val stopReason = (root["stop_reason"] as? JsonPrimitive)?.content
        if (stopReason == "refusal") throw LlmException("Model từ chối trả lời yêu cầu này.")

        // The reply is a list of blocks; thinking blocks are skipped, text blocks are joined.
        val text = (root["content"] as? JsonArray).orEmpty().mapNotNull { block ->
            val o = block as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.content != "text") return@mapNotNull null
            (o["text"] as? JsonPrimitive)?.content
        }.joinToString("")

        if (text.isBlank()) {
            val why = if (stopReason == "max_tokens") " (hết giới hạn độ dài)" else ""
            throw LlmException("Model không trả lời chữ nào$why: ${raw.take(200)}")
        }
        return text
    }

    private companion object {
        const val API_VERSION = "2026-10-21"
        const val MAX_TOKENS = 16000
    }
}
