package me.crsib.freecartracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.data.prefs.ThemeMode
import me.crsib.freecartracker.ui.CarTrackerApp
import me.crsib.freecartracker.ui.theme.CarTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preferences = (application as CarTrackerApp).container.preferences
        setContent {
            val themeMode by preferences.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CarTrackerTheme(darkTheme = darkTheme) {
                CarTrackerApp()
            }
        }
    }
}
