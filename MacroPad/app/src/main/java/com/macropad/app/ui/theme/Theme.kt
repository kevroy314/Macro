package com.macropad.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Custom dark color scheme matching health-cheat-sheet aesthetic
private val MacroPadColorScheme = darkColorScheme(
    // Primary colors
    primary = AccentLight,
    onPrimary = Background,
    primaryContainer = Accent,
    onPrimaryContainer = TextPrimary,

    // Secondary colors
    secondary = Teal,
    onSecondary = Background,
    secondaryContainer = SurfaceRaised,
    onSecondaryContainer = TextPrimary,

    // Tertiary colors
    tertiary = Gold,
    onTertiary = Background,
    tertiaryContainer = SurfaceRaised,
    onTertiaryContainer = TextPrimary,

    // Background colors
    background = Background,
    onBackground = TextPrimary,

    // Surface colors
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = TextMuted,

    // Outline colors
    outline = Border,
    outlineVariant = BorderLight,

    // Error colors
    error = Red,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Inverse colors
    inverseSurface = TextPrimary,
    inverseOnSurface = Background,
    inversePrimary = Accent
)

@Composable
fun MacroPadTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = MacroPadColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = Background.toArgb()
            window.navigationBarColor = Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
