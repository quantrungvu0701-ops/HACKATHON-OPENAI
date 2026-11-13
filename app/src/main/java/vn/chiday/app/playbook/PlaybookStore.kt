package vn.chiday.app.playbook

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vn.chiday.app.screen.ScreenSnapshot
import java.io.File

/**
 * Playbooks from two places: `assets/playbooks/` (shipped, hand-written) and
 * `filesDir/playbooks/` (created on the phone from pasted instructions). A phone copy with
 * the same id replaces the shipped one.
 */
class PlaybookStore(context: Context) {
    private val app = context.applicationContext
    private val userDir = File(app.filesDir, DIR)

    @Volatile
    private var cache: List<Playbook>? = null

    fun all(): List<Playbook> = cache ?: load().also { cache = it }

    fun userPlaybookIds(): Set<String> =
        userDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()

    private fun load(): List<Playbook> {
        val byId = LinkedHashMap<String, Playbook>()
        runCatching { app.assets.list(DIR).orEmpty() }.getOrDefault(emptyArray())
            .filter { it.endsWith(".json") }.sorted()
            .forEach { name ->
                parse(runCatching { app.assets.open("$DIR/$name").bufferedReader().use { it.readText() } }.getOrNull(), "asset $name")
                    ?.let { byId[it.id] = it }
            }
        userDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
            parse(runCatching { f.readText() }.getOrNull(), "file ${f.name}")?.let { byId[it.id] = it }
        }
        Log.i(TAG, "playbooks loaded: ${byId.keys}")
        return byId.values.toList()
    }

    private fun parse(text: String?, where: String): Playbook? {
        if (text == null) return null
        return runCatching { JSON.decodeFromString(Playbook.serializer(), text) }
            .onFailure { Log.w(TAG, "playbook $where did not parse: ${it.message?.take(160)}") }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() && it.steps.isNotEmpty() }
    }

    /** Saves a playbook created on the phone. Returns the stored copy. */
    fun save(playbook: Playbook): Playbook {
        userDir.mkdirs()
        val safeId = playbook.id.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifEmpty { "playbook" }
        val stored = playbook.copy(id = safeId)
        File(userDir, "$safeId.json").writeText(PRETTY.encodeToString(stored))
        cache = null
        return stored
    }

    fun delete(id: String) {
        File(userDir, "$id.json").delete()
        cache = null
    }

    /**
     * Picks the playbook for this screen: among those written for this app or website, the one
     * the user chose as their goal, else the one whose step signals match the visible text.
     * Null means generic mode.
     */
    fun select(snapshot: ScreenSnapshot, goal: String?): Playbook? {
        val candidates = all().filter { it.appliesTo(snapshot) }
        if (candidates.isEmpty()) return null
        val wanted = goal?.trim()?.lowercase().orEmpty()
        if (wanted.isNotEmpty()) {
            candidates.firstOrNull { it.goal.lowercase() == wanted }?.let { return it }
            candidates.firstOrNull { it.goal.lowercase().contains(wanted) || wanted.contains(it.goal.lowercase()) }?.let { return it }
        }
        return candidates.firstOrNull { it.likelyStep(snapshot) != null }
    }

    companion object {
        private const val TAG = "ChiDay"
        const val DIR = "playbooks"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
        val PRETTY = Json { prettyPrint = true; explicitNulls = false; encodeDefaults = false }
    }
}
