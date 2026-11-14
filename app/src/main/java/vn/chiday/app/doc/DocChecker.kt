package vn.chiday.app.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vn.chiday.app.llm.Llm
import vn.chiday.app.settings.LlmSettings
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** The vision model's answer about one document photo. Output is untrusted: all fields optional. */
@Serializable
data class DocVerdict(
    val ok: Boolean = false,
    val document: String? = null,
    val problems: List<String> = emptyList(),
    val advice: String? = null,
)

/** A photo made ready for upload: a bitmap for the screen and JPEG bytes for the model. */
class PreparedPhoto(val preview: Bitmap, val jpeg: ByteArray)

/**
 * Document photo check (MECHANISM.md §5): right document, four corners in frame, no glare or
 * blur, text readable. One vision call; the answer is in Vietnamese and says what to fix.
 */
object DocChecker {
    /** Long edge sent to the model. Enough to judge sharpness, small enough to upload quickly. */
    private const val MAX_EDGE = 1568
    private const val JPEG_QUALITY = 88

    const val SYSTEM_PROMPT =
        "You check a photo of a document before a Vietnamese user uploads it to a public-service " +
            "application, so that the application is not sent back. Look at the image and judge: " +
            "(1) is it the expected kind of document; (2) are all four corners inside the frame; " +
            "(3) is it sharp, with no blur; (4) is there glare, shadow or a finger covering text; " +
            "(5) can the printed text be read. For a portrait photo judge instead: face centred, " +
            "eyes open, plain background, no glare on glasses, not blurred. " +
            "Set ok=true only if the photo can be used as it is. Otherwise list each problem in one " +
            "short Vietnamese sentence and give one piece of advice on how to retake it. Address the " +
            "user as \"bác\", use everyday words. Do not read out or repeat personal details " +
            "(names, numbers, addresses) from the document. " +
            "Reply with JSON only: {ok, document, problems, advice}"

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /** Decodes with EXIF rotation applied, scales down, and re-encodes as JPEG. */
    suspend fun prepare(context: Context, uri: Uri): PreparedPhoto = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longEdge = max(info.size.width, info.size.height)
            if (longEdge > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / longEdge
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        PreparedPhoto(bitmap, out.toByteArray())
    }

    /** Throws LlmException on network or provider errors; the screen shows its message. */
    suspend fun check(settings: LlmSettings, photo: PreparedPhoto, expected: String): DocVerdict {
        val raw = Llm.clientFor(settings).complete(
            system = SYSTEM_PROMPT,
            user = "DOCCHECK\nEXPECTED: $expected",
            imageJpeg = photo.jpeg,
        )
        return parse(raw)
    }

    /** Lenient: strips code fences; if the model answered in prose, shows the prose as advice. */
    fun parse(raw: String): DocVerdict {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { lenient.decodeFromString(DocVerdict.serializer(), raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return DocVerdict(ok = false, advice = raw.trim().take(600))
    }
}
