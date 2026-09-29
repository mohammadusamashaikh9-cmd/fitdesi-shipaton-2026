package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyPersonalTrainerTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class HomeMetricDeckLayoutTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun `metric supporting words stay whole at increased font scale`() {
        setHomeContent(fontScale = 1.2f)
        assertMetricWordsFit()
        assertMetricsShareRow()
    }

    @Test
    fun `metric supporting words stay whole at 150 percent font scale`() {
        setHomeContent(fontScale = 1.5f)
        assertMetricWordsFit()
    }

    @Test
    fun `normal font scale keeps the three metrics side by side`() {
        setHomeContent(fontScale = 1f)
        assertMetricsShareRow()
    }

    private fun assertMetricsShareRow() {
        val metricTops = listOf("Sessions", "Duration", "Sets").map { label ->
            composeTestRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(metricTops[0], metricTops[1], 1f)
        assertEquals(metricTops[1], metricTops[2], 1f)
    }

    private fun setHomeContent(fontScale: Float) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalInspectionMode provides true,
            ) {
                MyPersonalTrainerTheme {
                    Box(Modifier.width(360.dp)) {
                        HomeScreen(
                            profileName = "Test",
                            todayConsumed = 0,
                            dailyGoal = 2000,
                            todayProtein = 0f,
                            todayCarbs = 0f,
                            todayFat = 0f,
                            proteinGoal = 0f,
                            carbsGoal = 0f,
                            fatGoal = 0f,
                            showMacroTargetProgress = false,
                            workoutLogs = emptyList(),
                            activePlanJson = "",
                            waterGlasses = 0,
                            onWaterIntakeChange = {},
                            onNavigateToCalories = {},
                            onSetMacroTargets = {},
                            onNavigateToExercises = {},
                            onOpenAiCoach = {},
                            showBasicPlusEntry = false,
                            onExplorePlus = {},
                        )
                    }
                }
            }
        }
    }

    private fun assertMetricWordsFit() {
        listOf("tracked", "completed").forEach { word ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(word).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult,
            ) { action -> action(layouts) }
            val layout = layouts.single()
            assertEquals("$word must remain whole", 1, layout.lineCount)
            assertTrue(
                "$word final glyph must fit inside its text box",
                layout.getBoundingBox(word.lastIndex).right <= layout.size.width + 2f,
            )
        }
    }
}
