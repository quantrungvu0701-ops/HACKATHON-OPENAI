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
            matchGoal(wanted, candidates)?.let { return it }
        }
        return candidates.firstOrNull { it.likelyStep(snapshot) != null }
    }

    companion object {
        private const val TAG = "ChiDay"

        // Words that say nothing about which task it is ("tôi muốn xem cần gì cho cháu").
        private val FILLER = setOf(
            "tôi", "muốn", "cần", "xem", "gì", "để", "cho", "của", "và", "với", "được", "làm",
            "bác", "cháu", "con", "em", "anh", "chị", "ơi", "nhé", "ạ", "à", "thế", "nào", "sao", "đi",
        )

        private fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

        /**
         * The playbook whose goal best matches what the user said in their own words, e.g.
         * "xin bản sao giấy khai sinh cho cháu" finds "Xem cần giấy tờ gì để xin bản sao Giấy
         * khai sinh". Needs at least two shared content words covering 40% of the goal.
         */
        fun matchGoal(said: String, playbooks: List<Playbook>): Playbook? {
            val heard = words(said).toSet()
            if (heard.isEmpty()) return null
            val lower = said.trim().lowercase()
            playbooks.firstOrNull { it.goal.lowercase() == lower }?.let { return it }
            return playbooks.mapNotNull { pb ->
                val goalWords = words(pb.goal).filter { it !in FILLER }.toSet()
                if (goalWords.isEmpty()) return@mapNotNull null
                val shared = goalWords.count { it in heard }
                val ratio = shared.toFloat() / goalWords.size
                if (shared >= 2 && ratio >= 0.4f) pb to ratio else null
            }.maxByOrNull { it.second }?.first
        }

        /** Shared by the activity and the service (same process): a save in one is seen by the other. */
        @Volatile
        private var cache: List<Playbook>? = null
        const val DIR = "playbooks"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
        val PRETTY = Json { prettyPrint = true; explicitNulls = false; encodeDefaults = false }
    }
}
