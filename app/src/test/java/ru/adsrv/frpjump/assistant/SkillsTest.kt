package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentTool
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.agent.ToolOutput
import ru.adsrv.frpjump.assistant.chat.AssistantToolFactory
import ru.adsrv.frpjump.assistant.chat.ConfirmationDecision
import ru.adsrv.frpjump.assistant.chat.ConfirmationSupport
import ru.adsrv.frpjump.assistant.chat.authorizeCall
import ru.adsrv.frpjump.assistant.tools.ChangeCheck
import ru.adsrv.frpjump.assistant.tools.ChangeRequest
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.ConfirmationPolicy
import ru.adsrv.frpjump.ssh.MemoryTextStorage
import ru.adsrv.frpjump.store.TextStorage
import java.io.File

/** Skills: the one type and registry, relevance, the list in the prompt, the tools and their gate, the store. */
class SkillsTest {
    private val on = AssistantTools(skills = true)
    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))
    private fun save(name: String = "new-skill", description: String = "Use when x.", requires: List<String> = emptyList(), body: String = "Steps.") =
        buildJsonObject { put("name", name); put("description", description); put("requires", JsonArray(requires.map(::JsonPrimitive))); put("body", body) }
    private fun named(name: String) = buildJsonObject { put("name", name) }
    private fun run(tool: AgentTool, args: JsonObject) = runBlocking { tool.execute(args) }

    // ---- the rule ----

    @Test fun `a skill is a kebab-case name, one short description line, known switches and a body within the size`() {
        fun make(name: String = "ok-name", description: String = "Use when x.", requires: List<String> = emptyList(), body: String = "b", max: Int = 100) =
            SkillRules.make(name, description, requires, body, max)
        assertNotNull(make())
        for (bad in listOf("a", "A-b", "ab_c", "-ab", "ab-", "a--b", "x".repeat(41), "", "has space", "ab.c")) assertNull(bad, make(name = bad))
        assertNotNull(make(name = "ab")); assertNotNull(make(name = "x".repeat(40))); assertNotNull(make(name = "a1-b2-c3"))
        assertNull(make(description = "")); assertNull(make(description = "two\nlines")); assertNull(make(description = "x".repeat(201))); assertNotNull(make(description = "x".repeat(200)))
        assertNull(make(requires = listOf("no_such_tool"))); assertNull(make(requires = listOf("ssh_command", "ssh_command")))
        assertNotNull(make(requires = listOf("ssh_command", "terminal_input")))
        // A tool without a switch of its own is not a switch name.
        assertNull(make(requires = listOf("terminal_read")))
        assertNull(make(body = "   ")); assertNull(make(body = "x".repeat(101))); assertNotNull(make(body = "x".repeat(100)))
        // Control and invisible characters are cleaned before the checks: what is saved is what is shown.
        assertEquals("a\nb", make(body = "a\u0007\nb‮")!!.body)
        assertEquals("Use when x.", make(description = "Use when x.‮")!!.description)
        assertEquals(SkillRules.knownSwitches(), AssistantToolId.switches.map { it.wire })
    }

    // ---- the registry: built-in and owner, one type ----

    @Test fun `the registry lists built-in then owner skills by name, and an owner skill never shadows a built-in one`() {
        val registry = SkillFixtures.registry(builtIn = listOf(SkillFixtures.authoring, SkillFixtures.builtInFree), owner = listOf(SkillFixtures.owner("zeta"), SkillFixtures.owner("alpha")))
        assertEquals(listOf("always-here", "skill-authoring", "alpha", "zeta"), registry.all().map { it.name })
        assertEquals(listOf(true, true, false, false), registry.all().map { it.builtIn })
        // A same-named owner skill in the file (written by hand, or before the built-in existed) is hidden by the built-in one.
        val storage = MemoryTextStorage()
        SkillStore(storage) { 4_000 }.put(SkillFixtures.owner("skill-authoring", body = "mine"))
        val shadowed = SkillFixtures.registry(storage = storage)
        assertEquals("Authoring text.", shadowed.find("skill-authoring")!!.body); assertEquals(1, shadowed.all().size)
        // The tool refuses the name and asks nothing.
        val tool = SkillSaveTool(shadowed)
        assertSame(ChangeCheck.ReadOnly, tool.changeCheck(save(name = "skill-authoring")))
        assertEquals(ToolOutput("Skill skill-authoring is built in and cannot be changed.", ToolOutcome.FAILED), run(tool, save(name = "skill-authoring")))
        assertEquals(ToolOutput("Skill skill-authoring is built in and cannot be deleted.", ToolOutcome.FAILED), run(SkillDeleteTool(shadowed), named("skill-authoring")))
    }

    @Test fun `a skill is relevant only while every switch it requires is on, and the list shows only those`() {
        val ssh = SkillFixtures.owner("ssh-only", requires = listOf("ssh_command"))
        val both = SkillFixtures.owner("tabs-and-input", requires = listOf("terminal_tabs", "terminal_input"))
        val free = SkillFixtures.owner("free")
        val registry = SkillFixtures.registry(builtIn = listOf(SkillFixtures.authoring), owner = listOf(ssh, both, free))
        fun names(tools: AssistantTools) = registry.relevant(tools).map { it.name }
        assertEquals(listOf("free"), names(AssistantTools()))
        assertEquals(listOf("skill-authoring", "free"), names(AssistantTools(skills = true)))
        assertEquals(listOf("free", "ssh-only"), names(AssistantTools(sshCommand = true)))
        // terminal_input is in effect only with the tabs on too (the effective state, not the raw switch).
        assertEquals(listOf("free"), names(AssistantTools(terminalInput = true)))
        assertEquals(listOf("free", "tabs-and-input"), names(AssistantTools(terminalTabs = true, terminalInput = true)))
        // A name that is no tool's is never on.
        val odd = SkillStore(MemoryTextStorage().also { it.write("""{"version":1,"skills":[{"name":"odd","description":"Use when x.","requires":["nope"],"body":"b"}]}""") }) { 4_000 }
        assertTrue("an unknown switch name is not a skill", odd.all().isEmpty())
        // What the prompt gets is the relevant ones, never more.
        assertEquals(listOf("- free: Use when restarting nginx."), registry.section(AssistantTools(), 2_000).lines)
    }

    @Test fun `the list in the request is cut to its budget, descriptions first and then whole entries with a pointer to skills_list`() {
        val skills = (1..10).map { Skill("skill-$it", "Use when " + "d".repeat(150), emptyList(), "b", false) }
        fun size(lines: List<String>) = lines.sumOf { it.length + 1 }
        // Fits as it is.
        val whole = skillsSection(skills, 5_000).lines
        assertEquals(10, whole.size); assertTrue(whole.all { it.endsWith("d".repeat(150)) })
        // Descriptions are shortened first, all kept, none under the floor.
        val shortened = skillsSection(skills, 1_000).lines
        assertEquals(10, shortened.size); assertTrue(size(shortened) <= 1_000); assertTrue(shortened.all { it.endsWith("…") && "skill-" in it })
        // Then whole entries go from the end, with the final line.
        val dropped = skillsSection(skills, 300).lines
        assertTrue(size(dropped) <= 300); assertTrue(dropped.last().matches(Regex("\\d+ more: call skills_list\\.")))
        val kept = dropped.size - 1
        assertEquals("${10 - kept} more: call skills_list.", dropped.last())
        assertEquals(skills.take(kept).map { it.name }, dropped.dropLast(1).map { it.removePrefix("- ").substringBefore(":") })
        // Nothing fits: only the pointer.
        assertEquals(listOf("10 more: call skills_list."), skillsSection(skills, 40).lines)
        assertEquals(emptyList<String>(), skillsSection(emptyList(), 100).lines)
        // The budget is the owner's, at the registry.
        val registry = SkillFixtures.registry(owner = skills.map { SkillFixtures.owner(it.name, it.description) })
        assertTrue(size(registry.section(AssistantTools(), 500).lines) <= 500)
        assertTrue(size(registry.section(AssistantTools(), AssistantLimits().skillsListChars).lines) <= AssistantLimits().skillsListChars)
    }

    @Test fun `a body is never in the prompt, only read_skill gives it`() {
        val secretBody = "STEP-ONE-BODY-MARKER"
        val registry = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("with-body", body = secretBody)))
        val section = registry.section(on, 2_000)
        assertTrue(section.lines.none { secretBody in it }); assertTrue(section.lines.any { it.startsWith("- with-body: ") })
        assertFalse(secretBody in TestPrompts.real.system(on, section))
        assertEquals(ToolOutput(secretBody), run(ReadSkillTool(registry, on, listed = true), named("with-body")))
    }

    // ---- read_skill ----

    @Test fun `read_skill returns a relevant skill's text and says so when there is none, pointing to skills_list only when it is offered`() {
        val registry = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("needs-ssh", requires = listOf("ssh_command"))))
        val tool = ReadSkillTool(registry, on, listed = true)
        assertEquals(ToolOutput("Authoring text."), run(tool, named("skill-authoring")))
        assertEquals(ToolOutput("No skill named \"nope-x\". Call skills_list.", ToolOutcome.FAILED), run(tool, named("nope-x")))
        // Not relevant now: as if it were not there.
        assertEquals(ToolOutput("No skill named \"needs-ssh\". Call skills_list.", ToolOutcome.FAILED), run(tool, named("needs-ssh")))
        assertEquals(ToolOutput("No skill named \"nope-x\".", ToolOutcome.FAILED), run(ReadSkillTool(registry, AssistantTools(), listed = false), named("nope-x")))
        assertEquals(ToolOutput("Provide name of a skill listed under Skills.", ToolOutcome.FAILED), run(tool, obj()))
        assertEquals(ToolOutput("Provide name of a skill listed under Skills.", ToolOutcome.FAILED), run(tool, buildJsonObject { put("name", "x"); put("extra", 1) }))
        // Reading is not a change: no question.
        assertSame(ChangeCheck.ReadOnly, tool.changeCheck(named("skill-authoring")))
        // The quoted name cannot carry anything but itself.
        assertTrue(run(tool, named("a\"b\nc")).text.startsWith("No skill named \"abc\"."))
    }

    @Test fun `skills_list shows every skill with its size, source and whether it is relevant`() {
        val registry = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("needs-ssh", requires = listOf("ssh_command"), body = "12345")))
        val text = run(SkillsListTool(registry, on), obj()).text.lines()
        assertEquals("name: skill-authoring | description: Use when the user asks to save what was done as a skill. | requires: skills_list | size: 15 characters | built-in | relevant now: yes", text[0])
        assertEquals("name: needs-ssh | description: Use when restarting nginx. | requires: ssh_command | size: 5 characters | owner | relevant now: no", text[1])
        assertEquals("No skills.", run(SkillsListTool(SkillFixtures.registry(builtIn = emptyList()), on), obj()).text)
        assertEquals(ToolOutput("skills_list takes no arguments.", ToolOutcome.FAILED), run(SkillsListTool(registry, on), buildJsonObject { put("x", 1) }))
        // Cut to the tool-output limit, whole lines.
        val many = SkillFixtures.registry(owner = (1..60).map { SkillFixtures.owner("skill-$it", "Use when " + "d".repeat(100)) })
        val cut = run(SkillsListTool(many, on, AssistantLimits(toolOutputChars = 2_000)), obj()).text
        assertTrue(cut.length <= 2_000); assertTrue(cut.lines().last().startsWith("truncated: ")); assertTrue(cut.lines().dropLast(1).all { it.startsWith("name: ") })
    }

    // ---- skill_save / skill_delete and the gate ----

    private class Asker(val answer: ConfirmationDecision, val mode: ConfirmationMode = ConfirmationMode.ASK) {
        val asked = ArrayList<ChangeRequest>()
        val support = ConfirmationSupport(policy = { ConfirmationPolicy(global = mode) }, persistOff = { true }, stamp = { "s" })
        suspend fun authorize(tool: AgentTool, arguments: JsonObject) = authorizeCall(support, mapOf(tool.spec.name to tool), ToolCall("id", tool.spec.name, arguments),
            wait = { request, _ -> asked += request; answer }, onNotSaved = {}, io = Dispatchers.Unconfined)
    }
    private fun kernel(asker: Asker, tool: AgentTool, arguments: JsonObject): ToolOutput = runBlocking { asker.authorize(tool, arguments) ?: tool.execute(arguments) }

    @Test fun `skill_save asks first, the card holds the whole skill, and saves only after Allow`() {
        val registry = SkillFixtures.registry()
        val tool = SkillSaveTool(registry)
        val body = "Purpose\n\tStep one\nStep two with <b>markup</b> and **marks**"
        val asker = Asker(ConfirmationDecision.ONCE)
        val out = kernel(asker, tool, save(name = "restart-nginx", description = "Use when nginx must restart.", requires = listOf("ssh_command"), body = body))
        assertEquals(ToolOutput("Skill restart-nginx is saved."), out)
        val request = asker.asked.single()
        assertEquals(AssistantToolId.SKILL_SAVE, request.tool); assertNull("no session grant for a skill", request.target)
        val card = request.skill!!
        assertEquals(SkillCard.Kind.SAVE, card.kind); assertEquals("restart-nginx", card.name); assertEquals("Use when nginx must restart.", card.description)
        assertEquals(listOf("ssh_command"), card.requires)
        // The whole text, as plain text: nothing cut or rendered (the tab is cleaned like every model text).
        assertEquals(body.replace("\t", ""), card.body); assertEquals(card.body, request.command); assertNull(card.oldBody)
        assertFalse("toString must not print the text", card.toString().contains("Step"))
        assertEquals(body.replace("\t", ""), registry.store.get("restart-nginx")!!.body)
    }

    @Test fun `a declined save saves nothing, and the gate cannot be skipped by running the tool without it`() {
        val registry = SkillFixtures.registry()
        val declined = kernel(Asker(ConfirmationDecision.DENY), SkillSaveTool(registry), save())
        assertEquals(ToolOutcome.DECLINED, declined.outcome); assertTrue(registry.store.all().isEmpty())
        // Without the support the gate refuses the call (a changing tool is never run unasked).
        val refused = runBlocking { authorizeCall(null, mapOf("skill_save" to SkillSaveTool(registry)), ToolCall("id", "skill_save", save()), wait = { _, _ -> ConfirmationDecision.ONCE }, onNotSaved = {}, io = Dispatchers.Unconfined) }
        assertEquals(ToolOutcome.FAILED, refused!!.outcome); assertTrue(registry.store.all().isEmpty())
        // The tool's own change check is a Change with a described card; a read tool never is.
        val check = SkillSaveTool(registry).changeCheck(save()) as ChangeCheck.Change
        assertTrue(check.request.described); assertNotNull(check.request.skill)
        assertTrue(ReadSkillTool(registry, on, true).changeCheck(named("skill-authoring")) === ChangeCheck.ReadOnly)
        // With the question off (the owner's choice) it saves at once, as every changing tool does.
        val asker = Asker(ConfirmationDecision.DENY, ConfirmationMode.OFF)
        assertEquals(ToolOutput("Skill new-skill is saved."), kernel(asker, SkillSaveTool(registry), save())); assertTrue(asker.asked.isEmpty())
    }

    @Test fun `replacing a skill says so on the card, keeps the old text reachable, and replies replaced`() {
        val registry = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("my-skill", body = "old text")))
        val asker = Asker(ConfirmationDecision.ONCE)
        val out = kernel(asker, SkillSaveTool(registry), save(name = "my-skill", body = "new text"))
        assertEquals(ToolOutput("Skill my-skill is replaced."), out)
        val card = asker.asked.single().skill!!
        assertEquals(SkillCard.Kind.REPLACE, card.kind); assertEquals("old text", card.oldBody); assertEquals("new text", card.body)
        assertEquals("new text", registry.store.get("my-skill")!!.body)
    }

    @Test fun `skill_save refuses what is not a skill, and a full store, without asking`() {
        val registry = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("one"), SkillFixtures.owner("two")))
        val limits = AssistantLimits(skillsMax = 2, skillChars = 500)
        val tool = SkillSaveTool(registry, limits)
        val invalid = ToolOutput("Provide name (kebab-case, 2–40), description (one line, up to 200 characters), requires (known switch names) and body (up to 500 characters).", ToolOutcome.FAILED)
        for (bad in listOf(save(name = "Bad Name"), save(description = "a\nb"), save(description = "x".repeat(201)), save(requires = listOf("no_such")), save(body = "x".repeat(501)),
            save(body = " "), obj("name" to JsonPrimitive("ok-name")), buildJsonObject { put("name", "ok-name"); put("description", "d"); put("requires", "ssh_command"); put("body", "b") })) {
            assertSame(bad.toString(), ChangeCheck.Invalid, tool.changeCheck(bad)); assertEquals(invalid, run(tool, bad))
        }
        assertTrue(tool.spec.description.contains("up to 500 characters"))
        // Full: a new name is refused with no question; replacing one that exists is not "new".
        assertSame(ChangeCheck.ReadOnly, tool.changeCheck(save(name = "three")))
        assertEquals(ToolOutput("Already 2 skills, the most allowed. Delete one first.", ToolOutcome.FAILED), run(tool, save(name = "three")))
        assertEquals(2, registry.store.all().size)
        assertTrue(tool.changeCheck(save(name = "one")) is ChangeCheck.Change)
        assertEquals(ToolOutput("Skill one is replaced."), run(tool, save(name = "one")))
    }

    @Test fun `skill_delete asks with the name and description, deletes only an owner skill, and says what it did`() {
        val registry = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("my-skill", description = "Use when a.")))
        val tool = SkillDeleteTool(registry)
        val asker = Asker(ConfirmationDecision.ONCE)
        assertEquals(ToolOutput("Skill my-skill is deleted."), kernel(asker, tool, named("my-skill")))
        val card = asker.asked.single().skill!!
        assertEquals(SkillCard.Kind.DELETE, card.kind); assertEquals(listOf("my-skill", "Use when a."), listOf(card.name, card.description))
        assertNull(registry.store.get("my-skill"))
        // Nothing to delete: no question, and the reply says so.
        assertSame(ChangeCheck.ReadOnly, tool.changeCheck(named("my-skill")))
        assertEquals(ToolOutput("No owner skill named \"my-skill\".", ToolOutcome.FAILED), run(tool, named("my-skill")))
        // Declined: still there.
        val again = SkillFixtures.registry(owner = listOf(SkillFixtures.owner("keep-me")))
        assertEquals(ToolOutcome.DECLINED, kernel(Asker(ConfirmationDecision.DENY), SkillDeleteTool(again), named("keep-me")).outcome)
        assertNotNull(again.store.get("keep-me"))
    }

    @Test fun `a store that cannot write changes nothing and the tool says so`() {
        val failing = object : TextStorage { override fun read(): String? = null; override fun write(text: String) = throw java.io.IOException("disk"); override fun delete() = Unit }
        val registry = SkillFixtures.registry(storage = failing)
        assertEquals(ToolOutput("Could not save the skill. Nothing was changed.", ToolOutcome.FAILED), run(SkillSaveTool(registry), save()))
        assertTrue(registry.store.all().isEmpty())
    }

    // ---- the store ----

    @Test fun `the owner's skills are saved to one file and read back, an absent or broken file is no skills, and a bad entry is left out`() {
        val storage = MemoryTextStorage()
        val store = SkillStore(storage) { 4_000 }
        assertTrue(store.all().isEmpty())
        assertTrue(store.put(SkillFixtures.owner("b-skill", requires = listOf("ssh_command"), body = "line1\nline2")))
        assertTrue(store.put(SkillFixtures.owner("a-skill")))
        val back = SkillStore(storage) { 4_000 }.all()
        assertEquals(listOf("a-skill", "b-skill"), back.map { it.name })
        assertEquals(listOf("ssh_command"), back[1].requires); assertEquals("line1\nline2", back[1].body); assertTrue(back.none { it.builtIn })
        assertTrue(store.delete("a-skill")); assertFalse(store.delete("a-skill"))
        assertEquals(listOf("b-skill"), SkillStore(storage) { 4_000 }.all().map { it.name })
        // Broken or alien files: no skills, nothing thrown.
        for (text in listOf("not json", "[]", "{\"skills\":\"x\"}", "")) assertTrue(text, SkillStore(MemoryTextStorage().also { it.write(text) }) { 4_000 }.all().isEmpty())
        // An entry that is not a skill (a bad name, a missing field) is left out; the rest stay.
        val mixed = """{"version":1,"skills":[{"name":"BAD","description":"d","body":"b"},{"name":"good-one","description":"Use when x.","body":"b"},{"description":"d"}]}"""
        assertEquals(listOf("good-one"), SkillStore(MemoryTextStorage().also { it.write(mixed) }) { 4_000 }.all().map { it.name })
        // A lowered size limit never loses a saved skill: it stays readable.
        assertEquals(1, SkillStore(storage) { 10 }.all().size)
    }

    @Test fun `the file is app-private and not backed up, and no skills file exists until a skill is saved`() {
        val app = File("src/main/java/ru/adsrv/frpjump/FrpJumpApp.kt").readText()
        assertTrue(app.contains("File(noBackupFilesDir, \"assistant_skills.json\")"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:allowBackup=\"false\""))
        assertNull(MemoryTextStorage().read())
    }

    // ---- the factory: what the model is offered ----

    private fun offered(tools: AssistantTools, registry: SkillRegistry?) = AssistantToolFactory.create(tools, AssistantChatRuntime(), AgentProvider { AgentReply("x") }, null, null, { }, skills = registry)
        .map { it.spec.name }.filter { it != "request_tool" }

    @Test fun `read_skill is offered while a skill is relevant, with no switch, and the writing tools only with the switch`() {
        val registry = SkillFixtures.registry(builtIn = listOf(SkillFixtures.authoring), owner = emptyList())
        // The built-in skill needs the switch: nothing relevant, nothing offered.
        assertEquals(emptyList<String>(), offered(AssistantTools(), registry).filter { "skill" in it })
        assertEquals(listOf("read_skill", "skills_list", "skill_save", "skill_delete"), offered(on, registry))
        // An owner skill that needs nothing is readable without the switch; the writing tools stay off.
        val free = SkillFixtures.registry(owner = listOf(SkillFixtures.owner()))
        assertEquals(listOf("read_skill"), offered(AssistantTools(), free))
        // No registry (a conversation without skills): none.
        assertEquals(emptyList<String>(), offered(on, null).filter { "skill" in it })
        // With the switch on but no skill relevant (an empty registry), only the writing tools.
        assertEquals(listOf("skills_list", "skill_save", "skill_delete"), offered(on, SkillFixtures.registry(builtIn = emptyList())))
        // request_tool offers the switch while it is off.
        assertTrue("skills_list" in AssistantToolId.switches.map { it.wire })
        assertTrue(AssistantTools().offerable(AssistantToolId.SKILLS_LIST))
    }

    // ---- the built-in skill ----

    @Test fun `the built-in skill-authoring ships as an asset, parses as a skill, fits 2,500 characters and keeps the rules`() {
        val dir = File("src/main/assets/assistant/skills")
        val skills = BuiltInSkills.load({ dir.list().orEmpty().toList() }, { File("src/main/assets/$it").inputStream() })
        assertEquals(listOf("skill-authoring"), skills.map { it.name })
        val skill = skills.single()
        assertTrue(skill.builtIn); assertEquals(listOf("skills_list"), skill.requires)
        assertEquals("Use when the user asks to save what was done as a skill, or to change or delete a skill.", skill.description)
        assertTrue("size ${skill.body.length}", skill.body.length <= 2_500)
        for (rule in listOf("kebab-case", "Use when", "requires", "Never put secrets, passwords, keys or tokens", "never grants authority", "Prefer facts learned in this conversation", "One skill per task")) assertTrue(rule, rule.lowercase() in skill.body.lowercase() || rule in skill.body)
    }

    @Test fun `a malformed built-in skill asset fails loudly`() {
        fun load(text: String) = BuiltInSkills.load({ listOf("a-skill.md") }, { text.byteInputStream() })
        assertEquals("a-skill", load("---\nname: a-skill\ndescription: Use when x.\nrequires:\n---\nbody").single().name)
        for (bad in listOf("no frontmatter", "---\nname: a-skill\n", "---\nname: other\ndescription: Use when x.\n---\nbody", "---\nname: a-skill\ndescription: Use when x.\nrequires: nope\n---\nbody", "---\nname: a-skill\ndescription: Use when x.\n---\n")) {
            assertTrue(bad, runCatching { load(bad) }.isFailure)
        }
    }
}
