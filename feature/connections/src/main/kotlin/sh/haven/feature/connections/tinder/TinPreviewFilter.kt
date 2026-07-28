package sh.haven.feature.connections.tinder

/**
 * 3–4 dòng cuối CÓ NGHĨA của snapshot_plain — port đúng heuristics pick_preview()
 * (Central_Command/03_toolkit/tin/tin_preview.py dòng 104–126, source of truth).
 * KHÁC pick_preview một điểm duy nhất: gom tối đa [maxLines] dòng thay vì 1.
 */
fun meaningfulTail(snapshotPlain: String?, maxLines: Int = 4): List<String> {
    if (snapshotPlain.isNullOrEmpty()) return emptyList()
    val lines = snapshotPlain.split('\n')
    
    val chromeRegex = Regex(
        "bypass permissions|shift\\+tab|esc to (cancel|interrupt)|to interrupt|\\? for shortcuts|ctrl\\+[a-z]|^\\s*\\d+ (lines?|tokens?)\\b|/clear to save|new task\\?|tokens? (left|remaining|used)",
        RegexOption.IGNORE_CASE
    )
    val promptRegex = Regex("[\\$#❯>]\\s*$")
    val boxRegex = Regex("[\\u2500-\\u259F\\s\\u258F\\u2595\\u00B7\\u2026|]")

    val result = mutableListOf<String>()
    for (i in lines.indices.reversed()) {
        val rawLine = lines[i]
        val s = rawLine.trim()
        if (s.isEmpty()) continue

        val core = s.replace(boxRegex, "")
        if (core == "" || core == ">" || core == "❯") continue

        if (chromeRegex.containsMatchIn(s)) continue

        if (promptRegex.containsMatchIn(s) && s.length < 60) continue

        val survival = s.trim('❯', '>', '│', ' ', '\u00A0', '\t').take(120)
        result.add(survival)
        if (result.size >= maxLines) break
    }
    return result.reversed()
}
