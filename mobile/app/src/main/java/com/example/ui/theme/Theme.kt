package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalThemeMode = compositionLocalOf { "Dark" }

private val FitDesiDarkColorScheme = darkColorScheme(
    primary = FitDesiOrange,
    onPrimary = FitDesiInk,
    primaryContainer = FitDesiOrangeSoftDark,
    onPrimaryContainer = FitDesiTextDark,
    secondary = FitDesiOrangePressed,
    onSecondary = FitDesiInk,
    secondaryContainer = FitDesiElevatedDark,
    onSecondaryContainer = FitDesiTextDark,
    tertiary = FitDesiInfo,
    onTertiary = FitDesiInk,
    background = FitDesiInk,
    onBackground = FitDesiTextDark,
    surface = FitDesiSurfaceDark,
    onSurface = FitDesiTextDark,
    surfaceVariant = FitDesiElevatedDark,
    onSurfaceVariant = FitDesiMutedDark,
    outline = FitDesiBorderDark,
    outlineVariant = FitDesiBorderDark.copy(alpha = 0.7f),
    error = FitDesiDanger,
    onError = FitDesiInk,
    errorContainer = Color(0xFF4D191B),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val FitDesiLightColorScheme = lightColorScheme(
    primary = FitDesiOrange,
    onPrimary = FitDesiInk,
    primaryContainer = FitDesiOrangeSoftLight,
    onPrimaryContainer = FitDesiInk,
    secondary = FitDesiOrangePressed,
    onSecondary = FitDesiInk,
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = FitDesiInk,
    tertiary = Color(0xFF006FA8),
    onTertiary = Color.White,
    background = FitDesiBackgroundLight,
    onBackground = FitDesiTextLight,
    surface = FitDesiSurfaceLight,
    onSurface = FitDesiTextLight,
    surfaceVariant = FitDesiElevatedLight,
    onSurfaceVariant = FitDesiMutedLight,
    outline = FitDesiBorderLight,
    outlineVariant = FitDesiBorderLight.copy(alpha = 0.75f),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val MaterialTheme.fitDesiColors: FitDesiExtendedColors
    @Composable get() = LocalFitDesiExtendedColors.current

@Composable
fun MyPersonalTrainerTheme(
    theme: String = LocalThemeMode.current,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (theme) {
        "Dark" -> true
        "Light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) FitDesiDarkColorScheme else FitDesiLightColorScheme
    val extendedColors = if (darkTheme) FitDesiDarkExtendedColors else FitDesiLightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalFitDesiExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = FitDesiShapes,
            content = content,
        )
    }
}
