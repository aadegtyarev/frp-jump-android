package ru.adsrv.frpjump.assistant

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.assistant.chat.needsEscape
import ru.adsrv.frpjump.settings.sanitizeModelText
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.store.TextStorage

/**
 * One skill: the owner's know-how for a task, loaded by the model on demand ([ReadSkillTool]). Built-in ones ship in the app (read-only), the owner's are written by the
 * model through `skill_save` after his confirmation; both are this ONE type, kept in ONE [SkillRegistry]. [requires] are tool names (the wire names of the tools that
 * own a switch): the skill is relevant while every one of those switches is on. Not a data class: its toString never prints the text.
 */
class Skill(val name: String, val description: String, val requires: List<String>, val body: String, val builtIn: Boolean) {
    override fun toString() = "Skill($name)"
}

/** The one rule for what a skill is, the same for the tools and for what is read back from a file. */
object SkillRules {
    val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    const val NAME_MIN = 2
    const val NAME_MAX = 40
    const val DESCRIPTION_MAX = 200

    /** The names a skill may require: the tools that own a switch. */
    fun knownSwitches(): List<String> = AssistantToolId.switches.map { it.wire }

    fun validName(name: String) = name.length in NAME_MIN..NAME_MAX && NAME.matches(name)

    /**
     * The skill that [name], [description], [requires] and [body] make, or null when they are not a skill: a kebab-case name of 2 to 40 characters; a description that is
     * printable one-line text of at most [DESCRIPTION_MAX] characters (it enters the system prompt on every Send, so NOTHING invisible or line-breaking is accepted in it, nor
     * cleaned away: any character the card would have to escape refuses the skill); known switch names, each once; a body that is not blank and has at most [maxChars]
     * characters (control and invisible characters are removed from it, [sanitizeModelText], the card showing it as it is saved).
     */
    fun make(name: String, description: String, requires: List<String>, body: String, maxChars: Int, builtIn: Boolean = false): Skill? {
        if (!validName(name)) return null
        val line = description.trim(' ')
        if (line.isEmpty() || line.length > DESCRIPTION_MAX || !printableLine(line)) return null
        val known = knownSwitches().toSet()
        if (requires.any { it !in known } || requires.toSet().size != requires.size) return null
        val text = sanitizeModelText(body).trim()
        if (text.isEmpty() || text.length > maxChars) return null
        return Skill(name, line, requires, text, builtIn)
    }

    /** Whether [text] is printable one-line text: no character that [needsEscape] flags (line and paragraph separators, format and zero-width characters, variation selectors, controls) except the plain space. */
    fun printableLine(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp != ' '.code && needsEscape(cp)) return false
        }
        return true
    }
}

/** What the owner's skills file holds. */
@Serializable
private class StoredSkill(val name: String, val description: String, val requires: List<String> = emptyList(), val body: String)

/** What a read of the file gave: the skills that parse, and the entries that do not (kept as they are, so that a write never loses them). */
private class Loaded(val skills: List<Skill>, val unparsed: List<JsonElement>)

/**
 * The owner's skills: one app-private file, written only through [put] and [delete] (the tools, after his confirmation). An absent file is no skills. A file that
 * exists but cannot be read (an I/O error, not JSON) is NOT cached as empty and refuses every write, so a later write can never replace what is there; an entry that is
 * not a skill (by [SkillRules]: an unknown switch name, a bad name) is not listed but is carried through every write unchanged. Thread-safe.
 */
class SkillStore(private val storage: TextStorage) {
    private var cache: Loaded? = null

    /** The owner's skills by name (none while the file cannot be read). */
    @Synchronized
    fun all(): List<Skill> = load()?.skills.orEmpty()

    @Synchronized
    fun get(name: String): Skill? = all().firstOrNull { it.name == name }

    /** False while the file exists and cannot be read (every write is then refused). */
    @get:Synchronized
    val readable: Boolean get() = load() != null

    /** Saves [skill] (replacing the one of its name); false when it could not be written or the file cannot be read (nothing then changed). */
    @Synchronized
    fun put(skill: Skill): Boolean {
        val loaded = load() ?: return false
        return write(loaded, (loaded.skills.filter { it.name != skill.name } + skill).sortedBy { it.name })
    }

    /** Deletes the skill [name]; false when there is none, it could not be written or the file cannot be read. */
    @Synchronized
    fun delete(name: String): Boolean {
        val loaded = load() ?: return false
        if (loaded.skills.none { it.name == name }) return false
        return write(loaded, loaded.skills.filter { it.name != name })
    }

    private fun write(loaded: Loaded, next: List<Skill>): Boolean = try {
        val entries = next.map { WireJson.encodeToJsonElement(StoredSkill(it.name, it.description, it.requires, it.body)) } + loaded.unparsed
        storage.write(WireJson.encodeToString(JsonObject(mapOf("version" to JsonPrimitive(1), "skills" to JsonArray(entries)))))
        cache = Loaded(next, loaded.unparsed)
        true
    } catch (e: Exception) {
        Log.w(TAG, "skills not saved: ${e.javaClass.simpleName}")
        false
    }

    /** The file as read, or null when it exists and cannot be read (then nothing is cached: the next call reads again). */
    private fun load(): Loaded? {
        cache?.let { return it }
        val loaded = try {
            val text = storage.read()?.takeIf { it.isNotBlank() }
            if (text == null) Loaded(emptyList(), emptyList())
            else {
                val entries = (WireJson.parseToJsonElement(text) as JsonObject)["skills"]?.let { it as JsonArray }.orEmpty()
                val skills = ArrayList<Skill>(); val unparsed = ArrayList<JsonElement>()
                for (entry in entries) {
                    // The size limit is the owner's now: one saved under a higher limit stays readable, so entries are not cut by length here.
                    val skill = runCatching { WireJson.decodeFromJsonElement<StoredSkill>(entry) }.getOrNull()
                        ?.let { SkillRules.make(it.name, it.description, it.requires, it.body, Int.MAX_VALUE) }
                    if (skill != null && skills.none { it.name == skill.name }) skills += skill else unparsed += entry
                }
                Loaded(skills.sortedBy { it.name }, unparsed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "skills not read: ${e.javaClass.simpleName}")
            null
        }
        return loaded?.also { cache = it }
    }

    private companion object { const val TAG = "SkillStore" }
}

/** What a built-in skill's file says. */
internal object BuiltInSkills {
    const val DIR = "assistant/skills"

    /**
     * Every `<name>.md` of [DIR] ([list] names the files): frontmatter `name`, `description` and `requires` (tool names, comma-separated, may be empty), then `---` and
     * the Markdown body. Strict like the prompt assets: a file that is malformed, whose name is not its file name, or that is not a skill fails the Send as a setup problem.
     */
    fun load(list: (String) -> List<String>, open: (String) -> java.io.InputStream): List<Skill> =
        list(DIR).filter { it.endsWith(".md") }.sorted().map { file ->
            val path = "$DIR/$file"
            val text = AssistantPrompts.readAsset(open, path)
            val lines = text.split('\n')
            require(lines.firstOrNull() == "---") { "Skill asset $path has no frontmatter" }
            val end = lines.drop(1).indexOf("---")
            require(end >= 0) { "Skill asset $path has an open frontmatter" }
            val fields = lines.subList(1, end + 1).associate { line ->
                val at = line.indexOf(':')
                require(at > 0) { "Skill asset $path: bad frontmatter line" }
                line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            val name = fields["name"].orEmpty()
            require(name == file.removeSuffix(".md")) { "Skill asset $path: name is not the file name" }
            val requires = fields["requires"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            SkillRules.make(name, fields["description"].orEmpty(), requires, lines.drop(end + 2).joinToString("\n"), Int.MAX_VALUE, builtIn = true)
                ?: throw IllegalArgumentException("Skill asset $path is not a skill")
        }
}

/** What goes under "Skills" in a request: the lines, already cut to the budget. */
class SkillsSection(val lines: List<String>)

/**
 * ONE registry of every skill: the built-in ones, then the owner's, each by name; an owner's skill never shadows a built-in one. What the model sees of it is
 * [section] (the relevant skills as `- <name>: <description>`, cut to a budget) and the tools; a body is only ever given by `read_skill`.
 */
class SkillRegistry internal constructor(private val builtInSkills: Lazy<List<Skill>>, val store: SkillStore) {
    /** Built-in first, then the owner's, each by name. */
    fun all(): List<Skill> {
        val builtIn = builtInSkills.value.sortedBy { it.name }
        val names = builtIn.map { it.name }.toSet()
        return builtIn + store.all().filter { it.name !in names }
    }

    fun find(name: String): Skill? = all().firstOrNull { it.name == name }

    fun builtInNames(): Set<String> = builtInSkills.value.map { it.name }.toSet()

    /**
     * Whether the skill is in effect for a request in which the switches are [tools]: every switch it requires is on (a name that is no tool's is never on), and an OWNER
     * skill only while «Скиллы» is on, so the owner has an off switch for a mistaken skill. Only the built-in skills whose requirements are on are in effect without it.
     */
    fun relevant(skill: Skill, tools: AssistantTools): Boolean =
        (skill.builtIn || tools[AssistantToolId.SKILLS_LIST]) && skill.requires.all { wire -> AssistantToolId.fromWire(wire)?.let { tools[it] } == true }

    fun relevant(tools: AssistantTools): List<Skill> = all().filter { relevant(it, tools) }

    /** The skills section for a request in which the switches are [tools], cut to [budget] characters (see [SkillsSection]); empty when no skill is relevant. */
    fun section(tools: AssistantTools, budget: Int): SkillsSection = skillsSection(relevant(tools), budget)
}

/** Shortest description kept before whole entries are dropped. */
private const val MIN_DESCRIPTION = 24

/**
 * `- <name>: <description>` per skill, within [budget] characters (a line counts its break): all as they are when they fit; else the descriptions shortened
 * (all to one length, the longest that fits, not under [MIN_DESCRIPTION]); else entries dropped from the end, with a final line `<n> more: call skills_list.`.
 */
internal fun skillsSection(skills: List<Skill>, budget: Int): SkillsSection {
    if (skills.isEmpty()) return SkillsSection(emptyList())
    fun lines(list: List<Skill>, cap: Int) = list.map { "- ${it.name}: ${cut(it.description, cap)}" }
    fun size(lines: List<String>) = lines.sumOf { it.length + 1 }
    val whole = lines(skills, SkillRules.DESCRIPTION_MAX)
    if (size(whole) <= budget) return SkillsSection(whole)
    for (cap in SkillRules.DESCRIPTION_MAX - 1 downTo MIN_DESCRIPTION) {
        val shortened = lines(skills, cap)
        if (size(shortened) <= budget) return SkillsSection(shortened)
    }
    for (kept in skills.size - 1 downTo 0) {
        val rest = lines(skills.take(kept), MIN_DESCRIPTION) + "${skills.size - kept} more: call skills_list."
        if (size(rest) <= budget) return SkillsSection(rest)
    }
    return SkillsSection(listOf("${skills.size} more: call skills_list."))
}

/** [text] cut to [cap] characters with an ellipsis, never inside a surrogate pair. */
private fun cut(text: String, cap: Int): String {
    if (text.length <= cap) return text
    var end = cap - 1
    if (end > 0 && text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end) + "…"
}
