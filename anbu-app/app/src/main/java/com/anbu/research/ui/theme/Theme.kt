package com.anbu.research.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AnbuDarkColorScheme = darkColorScheme(
    primary = AnbuCyan,
    onPrimary = AnbuBgDark,
    primaryContainer = AnbuCyanDark,
    onPrimaryContainer = AnbuTextPrimary,
    
    secondary = AnbuViolet,
    onSecondary = AnbuTextPrimary,
    secondaryContainer = AnbuVioletDark,
    onSecondaryContainer = AnbuVioletLight,
    
    tertiary = AnbuAmber,
    onTertiary = AnbuBgDark,
    tertiaryContainer = AnbuAmberDark,
    onTertiaryContainer = AnbuAmberLight,
    
    background = AnbuBgDark,
    onBackground = AnbuTextPrimary,
    
    surface = AnbuSurfaceDark,
    onSurface = AnbuTextPrimary,
    surfaceVariant = AnbuSurfaceElevated,
    onSurfaceVariant = AnbuTextSecondary,
    
    outline = AnbuBorder,
    outlineVariant = AnbuBorderGlow,
    
    error = AnbuCrimson,
    onError = AnbuTextPrimary
)

/**
 * Dark only, on purpose: the palette is built for a dark tactical surface and there
 * is no light scheme to fall back to. The window background is dark from
 * themes.xml, so this stays consistent through the whole launch.
 */
@Composable
fun AnbuTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = AnbuDarkColorScheme,
        typography = AnbuTypography,
        content = content
    )
}
