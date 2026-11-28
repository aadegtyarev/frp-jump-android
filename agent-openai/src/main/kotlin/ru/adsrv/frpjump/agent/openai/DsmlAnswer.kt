package ru.adsrv.frpjump.agent.openai

// DSML frames are not OpenAI structured tool calls. Only inspect assistant prose, not quoted examples.
private val dsmlFrame = Regex("<\\s*/?\\s*[|｜](?:\\s*[|｜])?\\s*DSML\\s*[|｜](?:\\s*[|｜])?\\s*(?:function_calls|tool_calls|calls|invoke|parameter)\\b", RegexOption.IGNORE_CASE)
private val paragraphEnd = Regex("\n[ \t]*\n")
private val fence = Regex("^ {0,3}(`{3,}|~{3,})")

internal fun containsUnquotedDsml(text: String): Boolean {
    if (!dsmlFrame.containsMatchIn(text)) return false
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val prose = normalized.toCharArray()
    var fenceChar: Char? = null
    var fenceLength = 0
    var offset = 0
    for (line in normalized.lineSequence()) {
        val found = fence.find(line)
        val marker = found?.groupValues?.get(1)
        val after = if (found == null) "" else line.substring(found.range.last + 1)
        val inFence = fenceChar != null
        if (inFence && marker != null && marker.first() == fenceChar && marker.length >= fenceLength && after.isBlank()) {
            fenceChar = null
        } else if (!inFence && marker != null && (marker.first() != '`' || '`' !in after)) {
            fenceChar = marker.first(); fenceLength = marker.length
        }
        if (inFence || fenceChar != null || line.trimStart().startsWith('>')) {
            for (i in offset until offset + line.length) prose[i] = ' '
        }
        offset += line.length + 1
    }
    val content = String(prose)
    var i = 0
    while (i < content.length) {
        if (content[i] == '`') {
            var end = i + 1
            while (end < content.length && content[end] == '`') end++
            val length = end - i
            val limit = paragraphEnd.find(content, end)?.range?.first ?: content.length
            var candidate = end
            while (candidate < limit) {
                candidate = content.indexOf('`', candidate)
                if (candidate < 0 || candidate >= limit) { candidate = -1; break }
                var next = candidate + 1
                while (next < content.length && content[next] == '`') next++
                if (next - candidate == length) { i = next; break }
                candidate = next
            }
            if (candidate >= 0 && candidate < limit) continue
            i = end; continue
        }
        if (content[i] == '"' && (i == 0 || content[i - 1] != '\\')) {
            var end = i + 1
            while (end < content.length && content[end] != '\n') {
                if (content[end] == '"' && content[end - 1] != '\\') break
                end++
            }
            if (end < content.length && content[end] == '"') { i = end + 1; continue }
        }
        if (content[i] == '<' && dsmlFrame.matchAt(content, i) != null) return true
        i++
    }
    return false
}
