package vn.chiday.app.llm

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POST {baseUrl}/chat/completions. Covers OpenAI, most gateways, and Gemini's
 * OpenAI-compatible endpoint. Only `model` and `messages` are sent: sampling and token-limit
 * parameter names differ between providers and model families, and a rejected parameter on
 * stage is worse than a default.
 */
class OpenAiCompatClient(baseUrl: String, private val model: String, private val apiKey: String) : LlmClient {

    private val url = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions") + "/chat/completions"

    override suspend fun complete(system: String, user: String, imageJpeg: ByteArray?): String {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(
                        buildJsonObject {
                            put("role", "user")
                            if (imageJpeg == null) {
                                put("content", user)
                            } else {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "text"); put("text", user) })
                                        add(
                                            buildJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    put("url", "data:image/jpeg;base64," + Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(Llm.JSON_MEDIA))
            .build()

        return parse(Llm.execute(request))
    }

    private fun parse(raw: String): String {
        val root = runCatching { Llm.json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw LlmException("Máy chủ trả về dữ liệu không phải JSON: ${raw.take(200)}") }
        val message = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            ?: throw LlmException("Trả lời không có «choices»: ${raw.take(200)}")
        val text = when (val content = message["content"]) {
            is JsonPrimitive -> content.takeIf { it.isString }?.content
            // Some gateways return the content as a list of parts.
            is JsonArray -> content.jsonArray.mapNotNull { part ->
                ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            }.joinToString("")
            else -> null
        }
        return text?.takeIf { it.isNotBlank() } ?: throw LlmException("Model không trả lời chữ nào: ${raw.take(200)}")
    }
}
