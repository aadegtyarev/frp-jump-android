package ru.adsrv.frpjump.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.locale.StringResources
import java.io.File

/**
 * R39: the Settings screen's groups and rows in the approved order, the labels as nouns or states (short, no trailing period), one distinct
 * Lucide icon per row on Settings and on the Assistant screen. The order and the icons are read from the screens' source, as the running UI
 * would otherwise be the only check (JVM tests run in the module directory).
 */
class SettingsLayoutTest {
    private val ru = StringResources("-ru")
    private val en = StringResources("")
    private val main = File("src/main/java/ru/adsrv/frpjump/ui/settings/SettingsScreen.kt").readText()
    private val assistant = File("src/main/java/ru/adsrv/frpjump/ui/settings/AssistantSettingsScreen.kt").readText()

    /** The `R.string.x` names in [text] among [wanted], in source order. */
    private fun order(text: String, wanted: List<String>): List<String> =
        Regex("R\\.string\\.([a-z_0-9]+)").findAll(text).map { it.groupValues[1] }.filter { it in wanted }.toList()

    private val mainBody = main.substring(main.indexOf("verticalArrangement = Arrangement.spacedBy(16.dp)"), main.indexOf("    when (open) {"))

    @Test fun `the groups of Settings come in the approved order`() {
        val groups = Regex("Group\\(stringResource\\(R\\.string\\.(\\w+)\\)\\)|(ProfileGroup)\\(").findAll(mainBody)
            .map { it.groupValues[1].ifEmpty { "settings_profile_group" } }.toList()
        assertEquals(
            listOf("settings_tunnels", "settings_background", "settings_security", "settings_terminal", "assistant_title", "settings_mqtt",
                "settings_appearance", "settings_profile_group", "settings_about"),
            groups,
        )
    }

    @Test fun `the rows of Settings come in the approved order`() {
        val rows = Regex("label = stringResource\\(R\\.string\\.(\\w+)\\)|(BatteryRow)\\(\\)|(VersionRow)\\(|(ProfileGroup)\\(").findAll(mainBody)
            .map { m -> m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } } } }.toList()
        assertEquals(
            listOf("setting_direct", "local_ports", "background_poll", "BatteryRow", "setting_app_lock", "setting_lock_after", "setting_hide_in_recents",
                "ssh_keys", "terminal_history_rows", "setting_terminal_direct_input", "terminal_text_input", "assistant_title", "mqtt_ingress_title",
                "mqtt_cache_title", "plugins_title", "language", "theme", "ProfileGroup", "VersionRow", "licenses"),
            rows,
        )
        val profile = main.substring(main.indexOf("private fun ProfileGroup"), main.indexOf("private fun BatteryRow"))
        assertTrue("Name and colour, then Forget profile last", profile.indexOf("R.string.name_and_color") < profile.indexOf("R.string.forget_profile)"))
        assertTrue("Forget profile is red with the log-out icon", profile.contains("labelColor = colors.error") && profile.contains("IconTile(LogOutGlyph, colors.errorContainer"))
        assertTrue("the lock-after row stays conditional", mainBody.contains("if (settings.appLock) {"))
        assertTrue("the battery row stays conditional", mainBody.contains("if (!batteryExempt) {"))
        assertTrue("the profile group stays conditional", mainBody.contains("profile?.let {"))
    }

    @Test fun `the Assistant screen has the master switch, then the setup, then the chat`() {
        val groups = listOf("settings_assistant_setup", "assistant_provider_title", "assistant_tools_row_hint", "chat_title")
        assertEquals(groups, order(assistant, groups).distinct())
        val calls = Regex("(AssistantModuleSetting|provider_entry|AssistantToolsEntry|AssistantOwnerInstructionsEntry|AssistantLimitsEntry|AssistantChatTextSizeSetting|AssistantChatEntry)\\(?")
            .findAll(assistant.substring(assistant.indexOf("Group(null)"), assistant.indexOf("/** The \"Open chat\" row"))).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("AssistantModuleSetting", "provider_entry", "AssistantToolsEntry", "AssistantOwnerInstructionsEntry", "AssistantLimitsEntry", "AssistantChatTextSizeSetting", "AssistantChatEntry"),
            calls,
        )
    }

    @Test fun `every row has its own Lucide icon and none is a text tile`() {
        fun glyphs(text: String) = Regex("IconTile\\((\\w+Glyph)").findAll(text).map { it.groupValues[1] }.toList()
        fun file(name: String) = File("src/main/java/ru/adsrv/frpjump/ui/settings/$name.kt").readText()
        val onMain = glyphs(main)
        assertEquals("one icon per row on Settings: $onMain", onMain.size, onMain.toSet().size)
        val onAssistant = glyphs(assistant) + glyphs(file("AssistantModuleSetting")) + glyphs(file("AssistantToolsScreen").substringBefore("internal fun AssistantToolsRoute")) +
            glyphs(file("AssistantOwnerInstructionsScreen")).take(1) + glyphs(file("AssistantLimitsScreen")).take(1) + glyphs(file("AssistantChatTextSize"))
        assertEquals(
            listOf("ServerGlyph", "MessageSquareGlyph", "BotGlyph", "WrenchGlyph", "PencilGlyph", "ClockGlyph", "ALargeSmallGlyph").sorted(),
            onAssistant.sorted(),
        )
        assertEquals("one icon per row on the Assistant screen: $onAssistant", onAssistant.size, onAssistant.toSet().size)
        assertFalse("no text tile on Settings", main.contains("GlyphTile("))
        assertFalse("no Material icon in a tile", mainBody.contains("Icons.Filled"))
    }

    @Test fun `labels are short nouns or states without a trailing period, in both languages`() {
        val labels = listOf(
            "setting_direct", "local_ports", "background_poll", "battery", "setting_app_lock", "setting_lock_after", "setting_hide_in_recents", "ssh_keys",
            "terminal_history_rows", "setting_terminal_direct_input", "terminal_text_input", "assistant_title", "assistant_enabled", "mqtt_ingress_title",
            "mqtt_cache_title", "plugins_title", "language", "theme", "name_and_color", "forget_profile", "licenses", "assistant_provider_title",
            "assistant_tools", "assistant_owner_title", "assistant_limits_title", "chat_text_size", "assistant_open_chat",
            "settings_tunnels", "settings_background", "settings_security", "settings_terminal", "settings_mqtt", "settings_appearance", "settings_about",
            "settings_assistant_setup",
            "setting_direct_hint", "background_poll_row_hint", "setting_hide_in_recents_hint", "setting_terminal_direct_input_hint", "terminal_text_input_hint",
            "mqtt_ingress_row_hint", "mqtt_cache_row_hint", "assistant_foundation_hint", "assistant_limits_hint", "assistant_row_off", "assistant_row_on_plain",
        )
        for (res in listOf(ru, en)) for (k in labels) {
            val text = checkNotNull(res.strings[k]) { k }
            assertFalse("$k ends with a period: $text", text.trimEnd().endsWith("."))
            assertTrue("$k is long: $text", text.length <= 48)
        }
    }

    @Test fun `the renamed labels and their mirrors`() {
        val expected = mapOf(
            "assistant_title" to ("Помощник" to "Assistant"), "assistant_enabled" to ("Помощник" to "Assistant"),
            "background_poll" to ("Проверка сервера" to "Server check"), "setting_lock_after" to ("Блокировка через" to "Lock after"),
            "setting_hide_in_recents" to ("Скрытие в недавних" to "Hide in recents"), "terminal_history_rows" to ("Строки истории" to "History lines"),
            "mqtt_ingress_title" to ("Входящие сообщения" to "Incoming messages"), "mqtt_cache_title" to ("Хранение" to "Storage"),
            "setting_direct" to ("Прямое соединение" to "Direct connection"), "assistant_provider_title" to ("Провайдер" to "Provider"),
            "settings_appearance" to ("Внешний вид" to "Appearance"), "settings_about" to ("О приложении" to "About"),
            "assistant_row_off" to ("Выключен" to "Off"),
        )
        for ((k, v) in expected) { assertEquals(k, v.first, ru.strings[k]); assertEquals(k, v.second, en.strings[k]) }
        assertEquals("Блокировка через 5 мин", ru.string("setting_app_lock_hint_after", "5 мин"))
        assertEquals("Включено 2 из 5", ru.string("plugins_row_hint", 2, 5))
        assertEquals("Включён · gpt", ru.string("assistant_row_on", "gpt"))
        assertEquals("gpt · окно 262K", ru.string("assistant_provider_row", "gpt", "262K"))
    }

    @Test fun `what a shortened row said stays in the screen it opens`() {
        // The fingerprint's needs-a-screen-lock and the lock details are in the toggle's own text and in the lock-after sheet's footer.
        assertTrue(ru.strings.getValue("setting_app_lock_needs_screen_lock").contains("блокировка экрана"))
        assertTrue(ru.strings.getValue("setting_lock_after_hint").contains("Туннели при этом работают"))
        assertTrue(en.strings.getValue("setting_lock_after_hint").contains("Tunnels keep running"))
        // The history hint is the sheet's footer, hide-in-recents says it may block screenshots.
        assertTrue(main.contains("Open.HISTORY") && main.contains("R.string.terminal_history_hint"))
        assertTrue(en.strings.getValue("setting_hide_in_recents_hint").contains("may block"))
        assertTrue(ru.strings.getValue("setting_hide_in_recents_hint").contains("может запретить"))
    }
}
