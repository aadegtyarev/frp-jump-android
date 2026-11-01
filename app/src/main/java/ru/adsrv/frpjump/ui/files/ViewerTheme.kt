package ru.adsrv.frpjump.ui.files

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import ru.adsrv.frpjump.ui.Palette

/**
 * One rule of the viewer's TextMate theme: the [scopes] it colours, with
 * one of the palette's text colours ([colour]) and a font [style]
 * (`italic`, `bold`, `underline`, or none).
 */
internal class SyntaxRule(val scopes: List<String>, val colour: (Palette) -> Long, val style: String = "")

private fun rule(vararg scopes: String, style: String = "", colour: (Palette) -> Long) = SyntaxRule(scopes.toList(), colour, style)

/**
 * The colours of the grammars' scopes, from the theme's own colours (on
 * the card, where each keeps AA contrast in light and dark): no colour of
 * its own is added for code. A more specific scope wins, as in TextMate.
 */
internal val SYNTAX_RULES: List<SyntaxRule> = listOf(
    rule("comment", "punctuation.definition.comment", style = "italic") { it.muted },
    rule("string", "markup.inline.raw", "markup.fenced_code.block", "markup.raw") { it.executable },
    rule("string.regexp") { it.profiles.getValue("crimson") },
    rule("constant.numeric") { it.profiles.getValue("blue") },
    rule("constant.language", "constant.character", "constant.other", "support.constant") { it.profiles.getValue("orange") },
    rule("keyword", "storage") { it.profiles.getValue("purple") },
    rule("keyword.operator", "punctuation") { it.ink },
    rule("punctuation.separator", "punctuation.definition.dictionary", "punctuation.definition.array") { it.muted },
    // Keys: JSON, YAML, INI and properties, JS object literals.
    rule(
        "support.type.property-name", "entity.name.tag.yaml", "keyword.other.definition.ini",
        "meta.object-literal.key", "entity.other.attribute-name",
    ) { it.accent },
    rule("entity.name.section", "markup.heading", "punctuation.definition.heading.markdown", style = "bold") { it.accent },
    rule("entity.name.type", "entity.name.class", "support.class", "support.type") { it.accent },
    rule("entity.name.function", "support.function") { it.profiles.getValue("blue") },
    rule("entity.name.tag") { it.profiles.getValue("purple") },
    // Shell `$variables`, `this` / `self`.
    rule(
        "variable.other.normal.shell", "variable.other.positional.shell", "variable.other.special.shell",
        "variable.other.bracket.shell", "punctuation.definition.variable.shell", "variable.language",
    ) { it.profiles.getValue("crimson") },
    // YAML anchors and aliases.
    rule("entity.name.type.anchor", "variable.other.alias", "punctuation.definition.anchor", "punctuation.definition.alias") {
        it.profiles.getValue("orange")
    },
    rule("markup.italic", style = "italic") { it.ink },
    rule("markup.bold", style = "bold") { it.ink },
    rule("markup.underline.link", "string.other.link", style = "underline") { it.profiles.getValue("blue") },
    // Markdown's marks: list bullets, quotes.
    rule("punctuation.definition.list.begin.markdown", "punctuation.definition.quote.begin.markdown", style = "bold") { it.archive },
    // Logs: time stamps; ERROR / FATAL / CRITICAL red, WARN amber, INFO / DEBUG / TRACE muted.
    rule("log.date") { it.profiles.getValue("blue") },
    rule("log.error", "log.exceptiontype", style = "bold") { it.error },
    rule("log.warning", style = "bold") { it.warning },
    rule("log.info", "log.debug", "log.verbose") { it.muted },
    rule("log.constant", "log.string", "log.exception") { it.ink },
    rule("invalid") { it.error },
)

/**
 * A rule's [colour] as written: tm4e takes a rule in the default colour
 * ([ink]) as "inherit", so an outer scope's colour would win (a log's
 * numbers are in `constant.language`); one step off in blue it is a colour
 * of its own, the same to the eye and in contrast.
 */
internal fun ruleColour(colour: Long, ink: Long): Long = if (colour == ink) colour xor 1 else colour

/** `#RRGGBB` of an ARGB colour. */
internal fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * The viewer's TextMate theme named [name], in VS Code's format: the
 * editor's ground and text from [p], then [SYNTAX_RULES].
 */
internal fun viewerThemeJson(p: Palette, name: String): String = buildJsonObject {
    put("name", name)
    putJsonObject("colors") {
        put("editor.background", hex(p.card))
        put("editor.foreground", hex(p.ink))
        put("editorLineNumber.foreground", hex(p.muted))
        put("editorLineNumber.activeForeground", hex(p.muted))
    }
    put(
        "tokenColors",
        buildJsonArray {
            add(buildJsonObject { putJsonObject("settings") { put("foreground", hex(p.ink)) } })
            for (r in SYNTAX_RULES) {
                add(
                    buildJsonObject {
                        put("scope", JsonArray(r.scopes.map(::JsonPrimitive)))
                        putJsonObject("settings") {
                            put("foreground", hex(ruleColour(r.colour(p), p.ink)))
                            // Always written: an empty style resets what an outer scope set (a log's date is in `comment`).
                            put("fontStyle", r.style)
                        }
                    },
                )
            }
        },
    )
}.toString()
