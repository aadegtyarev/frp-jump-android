package ru.adsrv.frpjump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.adsrv.frpjump.ui.AppNav
import ru.adsrv.frpjump.ui.FrpJumpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FrpJumpTheme {
                AppNav()
            }
        }
    }
}
