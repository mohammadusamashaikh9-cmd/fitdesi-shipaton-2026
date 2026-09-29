package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.fitDesiColors

enum class FitDesiMetricCardState {
    Content,
    Loading,
    Error,
    Empty,
    Disabled,
}

@Composable
fun FitDesiMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    state: FitDesiMetricCardState = FitDesiMetricCardState.Content,
) {
    val stateLabel = when (state) {
        FitDesiMetricCardState.Content -> "Available"
        FitDesiMetricCardState.Loading -> "Loading"
        FitDesiMetricCardState.Error -> "Error"
        FitDesiMetricCardState.Empty -> "Empty"
        FitDesiMetricCardState.Disabled -> "Disabled"
    }
    Surface(
        modifier = modifier
            .alpha(if (state == FitDesiMetricCardState.Disabled) 0.56f else 1f)
            .semantics { stateDescription = stateLabel },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                FitDesiMetricCardState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                FitDesiMetricCardState.Error -> Text(
                    text = supportingText ?: "This value could not be loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.fitDesiColors.danger,
                )
                FitDesiMetricCardState.Empty -> Text(
                    text = supportingText ?: "No data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FitDesiMetricCardState.Content,
                FitDesiMetricCardState.Disabled -> {
                    Text(text = value, style = MaterialTheme.typography.headlineMedium)
                    supportingText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardFoundationPreviewContent() {
    Column(
        modifier = Modifier.padding(FitDesiSpacing.content),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
            FitDesiMetricCard(
                label = "Metric",
                value = "24",
                supportingText = "Current value",
                modifier = Modifier.weight(1f),
            )
            FitDesiMetricCard(
                label = "Metric",
                value = "—",
                state = FitDesiMetricCardState.Loading,
                modifier = Modifier.weight(1f),
            )
        }
        FitDesiMetricCard(
            label = "Metric",
            value = "—",
            state = FitDesiMetricCardState.Empty,
            modifier = Modifier.fillMaxWidth(),
        )
        FitDesiMetricCard(
            label = "Metric",
            value = "—",
            state = FitDesiMetricCardState.Error,
            modifier = Modifier.fillMaxWidth(),
        )
        FitDesiMetricCard(
            label = "Metric",
            value = "24",
            state = FitDesiMetricCardState.Disabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Cards - Light", showBackground = true, widthDp = 320)
@Composable
private fun CardFoundationLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") { CardFoundationPreviewContent() }
}

@Preview(name = "Cards - Dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 320)
@Composable
private fun CardFoundationDarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") { CardFoundationPreviewContent() }
}
