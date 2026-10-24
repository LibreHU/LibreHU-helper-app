package org.librehu.helper

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.librehu.helper.ui.CarColors
import org.librehu.helper.ui.CarPalette
import org.librehu.helper.ui.CarTheme
import org.librehu.helper.ui.HelperScreen
import org.librehu.helper.ui.ThemeFollower

/** Report sections, root, anonymisation and export. */
class MainActivity : ComponentActivity() {
    private val theme =
        lazy {
            ThemeFollower(this) { dark, accent ->
                CarColors.palette = CarPalette.of(dark, if (accent != 0) Color(accent) else null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.value.start()
        Diagnostic.scanRoot(this)
        // Screen kept on while a report is written (long dumps).
        lifecycleScope.launch {
            Diagnostic.state.collect { s ->
                if (s is Diagnostic.State.Running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        setContent { CarTheme { HelperScreen() } }
    }

    override fun onDestroy() {
        theme.value.stop()
        super.onDestroy()
    }
}
