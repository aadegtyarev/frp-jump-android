package ru.adsrv.frpjump.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.Age
import ru.adsrv.frpjump.api.AgeUnit
import ru.adsrv.frpjump.locale.PluralRules
import ru.adsrv.frpjump.locale.StringResources

/** "Last seen 5 min ago" in both languages, through the resources [ageWords] picks. */
class AgeTextTest {
    private val english = StringResources("")
    private val russian = StringResources("-ru")

    private fun say(strings: StringResources, rules: (Int) -> String, age: Age): String {
        val words = ageWords(age)
        return if (words.plural) {
            strings.plural(resourceName(R.plurals::class.java, words.id), words.count, rules)
        } else {
            strings.string(resourceName(R.string::class.java, words.id))
        }
    }

    private fun en(unit: AgeUnit, count: Int = 0) = say(english, PluralRules::english, Age(unit, count))

    private fun ru(unit: AgeUnit, count: Int = 0) = say(russian, PluralRules::russian, Age(unit, count))

    @Test
    fun english() {
        assertEquals("just now", en(AgeUnit.NOW))
        assertEquals("12 s ago", en(AgeUnit.SECONDS, 12))
        assertEquals("5 min ago", en(AgeUnit.MINUTES, 5))
        assertEquals("1 h ago", en(AgeUnit.HOURS, 1))
        assertEquals("1 day ago", en(AgeUnit.DAYS, 1))
        assertEquals("3 days ago", en(AgeUnit.DAYS, 3))
        assertEquals("Last seen 5 min ago", english.string("device_last_seen", en(AgeUnit.MINUTES, 5)))
    }

    @Test
    fun russian() {
        assertEquals("только что", ru(AgeUnit.NOW))
        assertEquals("12 с назад", ru(AgeUnit.SECONDS, 12))
        assertEquals("21 мин назад", ru(AgeUnit.MINUTES, 21))
        assertEquals("11 ч назад", ru(AgeUnit.HOURS, 11))
        assertEquals("1 день назад", ru(AgeUnit.DAYS, 1))
        assertEquals("3 дня назад", ru(AgeUnit.DAYS, 3))
        assertEquals("5 дней назад", ru(AgeUnit.DAYS, 5))
        assertEquals("11 дней назад", ru(AgeUnit.DAYS, 11))
        assertEquals("21 день назад", ru(AgeUnit.DAYS, 21))
        assertEquals("22 дня назад", ru(AgeUnit.DAYS, 22))
        assertEquals("Был в сети 11 ч назад", russian.string("device_last_seen", ru(AgeUnit.HOURS, 11)))
    }

    /** The resource's name for its id, as `R` declares it. */
    private fun resourceName(type: Class<*>, id: Int): String = type.fields.first { it.getInt(null) == id }.name
}
