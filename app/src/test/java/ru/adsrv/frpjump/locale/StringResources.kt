package ru.adsrv.frpjump.locale

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's `strings.xml` of one `values*` directory as plain text, for
 * checks without Android: strings and plurals by name, untranslatable ones
 * marked. Tests run in the module directory.
 */
class StringResources(qualifier: String) {
    val strings = mutableMapOf<String, String>()
    val plurals = mutableMapOf<String, Map<String, String>>()
    val untranslatable = mutableSetOf<String>()

    init {
        val file = File("src/main/res/values$qualifier/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val children = root.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            if (e.getAttribute("translatable") == "false") untranslatable += name
            when (e.tagName) {
                "string" -> strings[name] = unescape(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    /** [name] with [args], as `getString` would format it. */
    fun string(name: String, vararg args: Any): String = checkNotNull(strings[name]) { name }.format(*args)

    /** Plurals [name] for [count], picked by [rules], formatted with [args] (by default the count). */
    fun plural(name: String, count: Int, rules: (Int) -> String, vararg args: Any): String {
        val forms = checkNotNull(plurals[name]) { name }
        val form = forms[rules(count)] ?: checkNotNull(forms["other"]) { "$name has no other" }
        return if (args.isEmpty()) form.format(count) else form.format(*args)
    }

    private fun unescape(text: String) = text.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}

/** CLDR plural categories for integers. */
object PluralRules {
    fun english(n: Int) = if (n == 1) "one" else "other"

    fun russian(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "one"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
        else -> "many"
    }
}
