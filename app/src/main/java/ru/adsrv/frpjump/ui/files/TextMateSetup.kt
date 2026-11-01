package ru.adsrv.frpjump.ui.files

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import ru.adsrv.frpjump.ui.Palette

/**
 * The viewer's TextMate grammars (assets/textmate/, see NOTICE.md there)
 * and its two themes, in Sora's process-wide registries: each grammar
 * read once, when a file first needs it, with the grammars it includes.
 */
internal object TextMateSetup {
    /** The asset of each scope: `textmate/<name>.tmLanguage.json`. */
    private val FILES = mapOf(
        "text.html.markdown" to "markdown",
        "text.log" to "log",
        "source.json" to "json",
        "source.yaml" to "yaml",
        "source.ini" to "ini",
        "source.shell" to "shell",
        "source.js" to "javascript",
        "source.python" to "python",
        "source.regexp.python" to "python-regexp",
        "source.go" to "go",
        "source.c" to "c",
        "source.lua" to "lua",
        "source.dockerfile" to "dockerfile",
        "source.sql" to "sql",
        "text.xml" to "xml",
        "text.html.basic" to "html",
        "source.css" to "css",
    )

    /**
     * The bundled grammars each one includes; others it names are not
     * bundled and stay plain. Markdown colours fenced code of every
     * bundled language.
     */
    private val INCLUDES: Map<String, List<String>> = mapOf(
        "source.python" to listOf("source.regexp.python"),
        "text.html.basic" to listOf("source.css", "source.js"),
        "source.lua" to listOf("source.c"),
        "text.html.markdown" to FILES.keys.filter { it != "text.html.markdown" },
    )

    private val loaded = HashSet<String>()

    /** Loads the grammar of [scope] and those it includes, from the app's assets. */
    @WorkerThread
    @Synchronized
    fun loadGrammar(context: Context, scope: String) {
        val registry = GrammarRegistry.getInstance()
        for (s in INCLUDES[scope].orEmpty() + scope) {
            if (s in loaded) continue
            val path = "textmate/${FILES.getValue(s)}.tmLanguage.json"
            val source = context.assets.open(path).use { IGrammarSource.fromInputStream(it, path, Charsets.UTF_8) }
            registry.loadGrammar(DefaultGrammarDefinition.withGrammarSource(source, s, s))
            loaded += s
        }
    }

    /**
     * Makes the theme of [palette] (light or [dark]) the current one, for
     * the grammars and the editors made after, and returns it. On the main
     * thread: the registry tells the editors' colour schemes at once.
     */
    @MainThread
    fun useTheme(palette: Palette, dark: Boolean): ThemeModel {
        // The grammar registry follows the theme registry once it exists.
        GrammarRegistry.getInstance()
        val themes = ThemeRegistry.getInstance()
        val name = if (dark) "frpjump-dark" else "frpjump-light"
        val model = themes.findThemeByFileName(name)
            ?: ThemeModel(IThemeSource.fromString(IThemeSource.ContentType.JSON, viewerThemeJson(palette, name)), name).also {
                it.isDark = dark
                themes.loadTheme(it, false)
            }
        themes.setTheme(model)
        return model
    }
}
