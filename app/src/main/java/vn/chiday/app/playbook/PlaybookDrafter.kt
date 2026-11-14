package vn.chiday.app.playbook

import vn.chiday.app.llm.Llm
import vn.chiday.app.settings.LlmSettings

/**
 * Turns instructions written for people (a school's 12-step guide, an agency's web page) into
 * a draft playbook (MECHANISM.md §3, way 2). The draft is never marked verified: a person
 * reviews it, and the overlay shows "chưa kiểm chứng" until someone checks it on a phone.
 */
object PlaybookDrafter {

    const val SYSTEM_PROMPT =
        "You convert step-by-step instructions written for people into a playbook for an " +
            "on-screen guide that circles one button at a time. Output JSON only, with this shape: " +
            "{\"id\": string, \"service\": string, \"match\": {\"packages\": [], \"hosts\": []}, " +
            "\"goal\": string, \"steps\": [{\"n\": number, \"signals\": [string], \"do\": string, " +
            "\"target_hint\": string, \"pitfall\": string, \"caution\": boolean}]}. " +
            "Rules: one user action per step, numbered from 1. \"signals\" are one to three short " +
            "texts that are visible on that step's screen, copied literally from the instructions " +
            "(button labels, headings); never invent text. \"target_hint\" is the exact visible " +
            "label of the element to press or fill in. \"do\" is a short Vietnamese sentence in " +
            "everyday words addressed to an older user. Add \"pitfall\" only if the instructions " +
            "warn about a mistake. Set \"caution\": true on steps that pay, submit or delete. " +
            "Do not add steps that are not in the instructions and do not include any personal data."

    /** Throws LlmException on provider errors and IllegalArgumentException if nothing usable came back. */
    suspend fun draft(settings: LlmSettings, service: String, host: String, goal: String, instructions: String): Playbook {
        val raw = Llm.clientFor(settings).complete(
            system = SYSTEM_PROMPT,
            user = "SERVICE: ${service.trim()}\nHOST: ${host.trim()}\nGOAL: ${goal.trim()}\nINSTRUCTIONS:\n${instructions.trim()}",
        )
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Model không trả về hướng dẫn: ${raw.take(160)}" }
        val parsed = runCatching { PlaybookStore.JSON.decodeFromString(Playbook.serializer(), raw.substring(start, end + 1)) }
            .getOrElse { throw IllegalArgumentException("Không đọc được hướng dẫn model trả về: ${it.message?.take(120)}") }
        require(parsed.steps.isNotEmpty()) { "Model không tìm thấy bước nào trong văn bản." }

        // What the user typed wins over what the model echoed, and a draft is never verified.
        return parsed.copy(
            id = slug(goal.ifBlank { parsed.goal }),
            service = service.trim().ifEmpty { parsed.service },
            goal = goal.trim().ifEmpty { parsed.goal },
            match = Playbook.Match(packages = emptyList(), hosts = listOf(cleanHost(host)).filter { it.isNotEmpty() }),
            verified = null,
            steps = parsed.steps.mapIndexed { i, s -> s.copy(n = i + 1) },
        )
    }

    fun cleanHost(input: String): String =
        input.trim().lowercase().substringAfter("://").substringBefore('/').removePrefix("www.")

    private fun slug(text: String): String {
        val ascii = java.text.Normalizer.normalize(text.lowercase().replace('đ', 'd'), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return "nhap-" + ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "huong-dan" }
    }
}
