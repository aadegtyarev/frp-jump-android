package ru.adsrv.frpjump.locale

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/**
 * Strings for code that has no screen at hand (failures kept in state, the
 * SSH console's notes), in the app's language at the time they are made.
 * Screens use their own context instead.
 */
object Texts {
    @Volatile
    private var app: Context? = null

    @Volatile
    private var cached: Pair<String, Context>? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any): String =
        context()?.getString(id, *args) ?: unresolved(id, args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context()?.resources?.getQuantityString(id, count, *args) ?: unresolved(id, args)

    private fun context(): Context? {
        val base = app ?: return null
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        cached?.takeIf { it.first == tags }?.let { return it.second }
        return base.localized().also { cached = tags to it }
    }

    /** JVM unit tests run without the app: the id and the arguments, enough to tell texts apart. */
    private fun unresolved(id: Int, args: Array<out Any>) = (listOf(id) + args).joinToString(" ")
}
