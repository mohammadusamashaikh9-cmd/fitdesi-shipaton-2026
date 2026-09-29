package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// FitDesi-owned brand colors from fitdesi.tokens.json.
val FitDesiOrange = Color(0xFFFF6A00)
val FitDesiOrangePressed = Color(0xFFD95700)
val FitDesiOrangeSoftDark = Color(0xFF3A210F)
val FitDesiOrangeSoftLight = Color(0xFFFFE9D8)

val FitDesiInk = Color(0xFF0E0F11)
val FitDesiSurfaceDark = Color(0xFF17191D)
val FitDesiElevatedDark = Color(0xFF25282E)
val FitDesiBorderDark = Color(0xFF343840)
val FitDesiTextDark = Color(0xFFF7F8FA)
val FitDesiMutedDark = Color(0xFFAEB3BC)
val FitDesiDisabledDark = Color(0xFF676D77)

// Light counterparts preserve the existing app's System/Light/Dark contract.
val FitDesiBackgroundLight = Color(0xFFF7F8FA)
val FitDesiSurfaceLight = Color(0xFFFFFFFF)
val FitDesiElevatedLight = Color(0xFFF0F2F5)
val FitDesiBorderLight = Color(0xFFD8DCE2)
val FitDesiTextLight = FitDesiInk
val FitDesiMutedLight = Color(0xFF5D636D)
val FitDesiDisabledLight = Color(0xFF8A9099)

val FitDesiSuccess = Color(0xFF16C784)
val FitDesiInfo = Color(0xFF00A8FF)
val FitDesiWarning = Color(0xFFFFB020)
val FitDesiDanger = Color(0xFFFF4D4F)

/** Semantic colors that do not have dedicated Material 3 ColorScheme roles. */
@Immutable
data class FitDesiExtendedColors(
    val success: Color,
    val info: Color,
    val warning: Color,
    val danger: Color,
    val elevatedSurface: Color,
    val border: Color,
    val mutedContent: Color,
    val disabledContent: Color,
)

internal val FitDesiDarkExtendedColors = FitDesiExtendedColors(
    success = FitDesiSuccess,
    info = FitDesiInfo,
    warning = FitDesiWarning,
    danger = FitDesiDanger,
    elevatedSurface = FitDesiElevatedDark,
    border = FitDesiBorderDark,
    mutedContent = FitDesiMutedDark,
    disabledContent = FitDesiDisabledDark,
)

internal val FitDesiLightExtendedColors = FitDesiExtendedColors(
    success = Color(0xFF087A50),
    info = Color(0xFF006FA8),
    warning = Color(0xFF8A5A00),
    danger = Color(0xFFB3261E),
    elevatedSurface = FitDesiElevatedLight,
    border = FitDesiBorderLight,
    mutedContent = FitDesiMutedLight,
    disabledContent = FitDesiDisabledLight,
)

internal val LocalFitDesiExtendedColors = staticCompositionLocalOf {
    FitDesiDarkExtendedColors
}

// Compatibility aliases retained until existing screens migrate to semantic roles.
val VibrantRed = Color(0xFFE94560)
val OffWhite = Color(0xFFF8F9FA)
val Gold = Color(0xFFFFD700)
val DarkGray = Color(0xFF1A1A1D)
val CardDark = Color(0xFF24242C)
val CardLight = Color(0xFFFFFFFF)
val TextDark = Color(0xFF1E2022)
val TextLight = Color(0xFFE9ECEF)
val BorderLight = Color(0xFFE9ECEF)
val AppBackground = Color(0xFF0F0F1A)
val AppSurface = Color(0xFF1A1A2E)
val AppPrimary = Color(0xFFE94560)
val AppSecondary = Color(0xFF00D4FF)
val AppAccent = Color(0xFFFFD700)
val AppTextPrimary = Color(0xFFFFFFFF)
val AppTextSecondary = Color(0xFFA0A0B8)
