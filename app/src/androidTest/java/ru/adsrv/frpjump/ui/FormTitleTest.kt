package ru.adsrv.frpjump.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R

/** The title of a form is whole at any font size: the bar grows with it (up to three lines) and never cuts it with an ellipsis. */
@RunWith(AndroidJUnit4::class)
class FormTitleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun layoutOf(text: String): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test fun theLongestFormTitlesAreNotEllipsizedAndTheBodyStartsBelowThem() {
        val args = InstrumentationRegistry.getArguments()
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(args.getString("chatLocale") ?: "en")) }
        val context = compose.activity.createConfigurationContext(config)
        val titles = listOf(R.string.mqtt_ingress_title, R.string.assistant_provider_title, R.string.assistant_limits_title).map { context.getString(it) }
        var index = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context, androidx.compose.ui.platform.LocalConfiguration provides config) {
                FrpJumpTheme { FormScreen(titles[index.intValue], "Save", false, {}, {}) { androidx.compose.material3.Text("body", modifier = androidx.compose.ui.Modifier) } }
            }
        }
        for (i in titles.indices) {
            compose.runOnIdle { index.intValue = i }
            compose.onNodeWithText(titles[i]).assertIsDisplayed()
            val layout = layoutOf(titles[i])
            assertEquals(titles[i], layout.layoutInput.text.text)
            assertFalse("${titles[i]} is cut", layout.hasVisualOverflow)
            assertTrue("${titles[i]}: ${layout.lineCount} lines", layout.lineCount <= 3)
            for (line in 0 until layout.lineCount) assertFalse("${titles[i]} line $line is ellipsized", layout.isLineEllipsized(line))
            val title = compose.onNodeWithText(titles[i]).fetchSemanticsNode().boundsInRoot
            val body = compose.onNodeWithText("body").fetchSemanticsNode().boundsInRoot
            assertTrue("the body starts at ${body.top}, under the title that ends at ${title.bottom}", body.top >= title.bottom - 1f)
        }
    }
}
