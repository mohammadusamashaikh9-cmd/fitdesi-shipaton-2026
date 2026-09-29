package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme
import kotlin.math.roundToInt

@Composable
fun FitDesiProgressBar(
    label: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val boundedProgress = progress?.coerceIn(0f, 1f)
    val readableProgress = supportingText ?: boundedProgress?.let {
        "${(it * 100).roundToInt()}%"
    } ?: "In progress"

    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.56f)
            .semantics {
                stateDescription = if (enabled) readableProgress else "Disabled"
            },
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = readableProgress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boundedProgress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FitDesiDimensions.progressHeight),
            )
        } else {
            LinearProgressIndicator(
                progress = { boundedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FitDesiDimensions.progressHeight),
            )
        }
    }
}

@Composable
private fun ProgressFoundationPreviewContent() {
    Column(
        modifier = Modifier.padding(FitDesiSpacing.content),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.large),
    ) {
        FitDesiProgressBar(label = "Progress", progress = 0.68f)
        FitDesiProgressBar(label = "Loading", progress = null)
        FitDesiProgressBar(label = "Unavailable", progress = 0.2f, enabled = false)
    }
}

@Preview(name = "Progress - Light", showBackground = true, widthDp = 320)
@Composable
private fun ProgressFoundationLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") { ProgressFoundationPreviewContent() }
}

@Preview(name = "Progress - Dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 320)
@Composable
private fun ProgressFoundationDarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") { ProgressFoundationPreviewContent() }
}
