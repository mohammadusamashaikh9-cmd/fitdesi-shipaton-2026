package com.example.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeTest {
    @Test
    fun `Sora family bundles the approved application weights`() {
        val expectedFamily = FontFamily(
            Font(R.font.sora_regular, FontWeight.Normal),
            Font(R.font.sora_medium, FontWeight.Medium),
            Font(R.font.sora_semibold, FontWeight.SemiBold),
            Font(R.font.sora_bold, FontWeight.Bold),
        )

        assertEquals(expectedFamily, SoraFontFamily)
        assertEquals(SoraFontFamily, FitDesiSansFontFamily)
        assertEquals(SoraFontFamily, InterFontFamily)
        assertEquals(SoraFontFamily, OswaldFontFamily)
        assertEquals(SoraFontFamily, RobotoMonoFontFamily)
    }

    @Test
    fun `Material typography keeps the existing semantic weight hierarchy`() {
        val boldStyles = listOf(
            Typography.displayLarge,
            Typography.displayMedium,
            Typography.displaySmall,
            Typography.headlineLarge,
            Typography.headlineMedium,
            Typography.headlineSmall,
        )
        val semiBoldStyles = listOf(
            Typography.titleLarge,
            Typography.titleMedium,
            Typography.titleSmall,
            Typography.labelLarge,
        )
        val mediumStyles = listOf(Typography.labelMedium, Typography.labelSmall)
        val regularStyles = listOf(
            Typography.bodyLarge,
            Typography.bodyMedium,
            Typography.bodySmall,
        )

        (boldStyles + semiBoldStyles + mediumStyles + regularStyles).forEach { style ->
            assertEquals(SoraFontFamily, style.fontFamily)
        }
        boldStyles.forEach { style -> assertEquals(FontWeight.Bold, style.fontWeight) }
        semiBoldStyles.forEach { style -> assertEquals(FontWeight.SemiBold, style.fontWeight) }
        mediumStyles.forEach { style -> assertEquals(FontWeight.Medium, style.fontWeight) }
        regularStyles.forEach { style -> assertEquals(FontWeight.Normal, style.fontWeight) }
    }
}
