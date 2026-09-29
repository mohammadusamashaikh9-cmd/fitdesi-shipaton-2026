package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme

@Composable
fun FitDesiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .defaultMinSize(minHeight = FitDesiDimensions.minimumTouchTarget)
            .semantics {
                if (loading) stateDescription = "Loading"
            },
        shape = MaterialTheme.shapes.medium,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(FitDesiSpacing.extraSmall))
        } else if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(FitDesiSpacing.extraSmall))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FitDesiSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = FitDesiDimensions.minimumTouchTarget),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(FitDesiSpacing.extraSmall))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ButtonFoundationPreviewContent() {
    Column(
        modifier = Modifier.padding(FitDesiSpacing.content),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
            FitDesiPrimaryButton(text = "Continue", onClick = {})
            FitDesiSecondaryButton(text = "Back", onClick = {})
        }
        FitDesiPrimaryButton(text = "Saving", onClick = {}, loading = true)
        FitDesiPrimaryButton(text = "Unavailable", onClick = {}, enabled = false)
    }
}

@Preview(name = "Buttons - Light", showBackground = true, widthDp = 320)
@Composable
private fun ButtonFoundationLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") { ButtonFoundationPreviewContent() }
}

@Preview(name = "Buttons - Dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 320)
@Composable
private fun ButtonFoundationDarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") { ButtonFoundationPreviewContent() }
}
