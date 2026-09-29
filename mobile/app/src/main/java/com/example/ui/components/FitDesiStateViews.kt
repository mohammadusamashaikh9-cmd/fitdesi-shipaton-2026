package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.fitDesiColors

@Composable
fun FitDesiLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    StateContainer(
        modifier = modifier.semantics { stateDescription = "Loading" },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun FitDesiErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateContainer(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = "Error"
        },
    ) {
        StateTitle(text = title, color = MaterialTheme.fitDesiColors.danger)
        StateMessage(message)
        if (actionLabel != null && onAction != null) {
            FitDesiPrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun FitDesiEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateContainer(
        modifier = modifier.semantics { stateDescription = "Empty" },
    ) {
        StateTitle(text = title)
        StateMessage(message)
        if (actionLabel != null && onAction != null) {
            FitDesiSecondaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
private fun StateContainer(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FitDesiSpacing.content),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
        content = { content() },
    )
}

@Composable
private fun StateTitle(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StateMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StateFoundationPreviewContent() {
    Column(modifier = Modifier.padding(FitDesiSpacing.extraSmall)) {
        FitDesiLoadingState(message = "Loading content")
        FitDesiErrorState(
            title = "Could not load",
            message = "Try again when you are ready.",
            actionLabel = "Retry",
            onAction = {},
        )
        FitDesiEmptyState(
            title = "Nothing here yet",
            message = "Complete the first step to see content.",
            actionLabel = "Get started",
            onAction = {},
        )
    }
}

@Preview(name = "States - Light", showBackground = true, widthDp = 320)
@Composable
private fun StateFoundationLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") { StateFoundationPreviewContent() }
}

@Preview(name = "States - Dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 320)
@Composable
private fun StateFoundationDarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") { StateFoundationPreviewContent() }
}

@Preview(name = "States - 360dp Large Text", showBackground = true, widthDp = 360, fontScale = 1.5f)
@Composable
private fun StateFoundationLargeTextPreview() {
    MyPersonalTrainerTheme(theme = "Light") { StateFoundationPreviewContent() }
}
