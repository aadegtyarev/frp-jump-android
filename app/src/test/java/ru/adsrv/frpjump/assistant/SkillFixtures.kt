package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.ssh.MemoryTextStorage
import ru.adsrv.frpjump.store.TextStorage

/** Skills in memory: a built-in one that needs the skills switch, an owner's that needs nothing, and a registry over them. */
internal object SkillFixtures {
    val authoring = Skill("skill-authoring", "Use when the user asks to save what was done as a skill.", listOf("skills_list"), "Authoring text.", true)
    val builtInFree = Skill("always-here", "Use when anything is asked.", emptyList(), "Built-in text.", true)
    fun owner(name: String = "my-skill", description: String = "Use when restarting nginx.", requires: List<String> = emptyList(), body: String = "Steps.") =
        Skill(name, description, requires, body, false)

    fun registry(builtIn: List<Skill> = listOf(authoring), owner: List<Skill> = emptyList(), storage: TextStorage = MemoryTextStorage(), maxChars: Int = 4_000): SkillRegistry {
        val store = SkillStore(storage)
        owner.forEach { check(store.put(it)) }
        return SkillRegistry(lazy { builtIn }, store)
    }
}
