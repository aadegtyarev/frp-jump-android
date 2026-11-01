package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A password-style field, hidden until the eye is tapped. [saved]: a
 * value is kept already; while the field is empty it shows that (masked
 * dots and "saved") instead of looking empty, and typing replaces it.
 * [contentType] lets a password manager fill it in; a field showing a
 * saved password offers that only once it is tapped, so filling in the
 * user name does not quietly replace the saved password.
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    saved: String? = null,
    savedSpoken: String? = null,
    contentType: ContentType? = null,
) {
    var shown by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Material shows a placeholder under a resting label only without one: the saved hint takes the label's place until typing starts.
    val showSaved = saved != null && value.isEmpty()
    val fillable = contentType?.takeIf { offersFill(saved != null, focused, value.isNotEmpty()) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (showSaved) null else ({ Text(label) }),
        // The dots are for the eye: TalkBack hears "Password, saved".
        placeholder = if (showSaved) ({ Text(saved.orEmpty(), modifier = Modifier.clearAndSetSemantics {}) }) else null,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (showSaved) Modifier.semantics { contentDescription = savedSpoken ?: label } else Modifier)
            .then(if (fillable != null) Modifier.semantics { this.contentType = fillable } else Modifier),
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) EyeOff else Eye, contentDescription = stringResource(if (shown) R.string.secret_hide else R.string.secret_show))
            }
        },
    )
}

/**
 * Whether a password manager may fill a secret field in: always, unless it
 * shows a [saved] password and the owner has neither tapped it ([focused])
 * nor typed in it ([typed]); filling in the user name then leaves the saved
 * password alone.
 */
fun offersFill(saved: Boolean, focused: Boolean, typed: Boolean): Boolean = !saved || focused || typed

// Material "visibility" / "visibility_off" outlines, to avoid pulling in material-icons-extended.
private val Eye = eyeIcon(
    "Eye",
    "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
        "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
)

private val EyeOff = eyeIcon(
    "EyeOff",
    "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 " +
        "-1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
        "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84" +
        "l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
        "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08" +
        "l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
        "m4.31,-0.78l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
)

private fun eyeIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()
