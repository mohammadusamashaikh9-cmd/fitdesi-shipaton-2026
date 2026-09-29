package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme

@Composable
fun FitDesiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val helper = errorText ?: supportingText
    val supportingContent: (@Composable () -> Unit)? = if (helper != null) {
        @Composable {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        null
    }
    val placeholderContent: (@Composable () -> Unit)? = if (placeholder != null) {
        @Composable { Text(placeholder) }
    } else {
        null
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholderContent,
        supportingText = supportingContent,
        isError = errorText != null,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    )
}

@Composable
private fun InputFoundationPreviewContent() {
    Column(
        modifier = Modifier.padding(FitDesiSpacing.content),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
    ) {
        FitDesiTextField(
            value = "",
            onValueChange = {},
            label = "Question",
            placeholder = "Enter text",
            supportingText = "Supporting guidance",
            modifier = Modifier.fillMaxWidth(),
        )
        FitDesiTextField(
            value = "Invalid value",
            onValueChange = {},
            label = "Question",
            errorText = "Check this value and try again.",
            modifier = Modifier.fillMaxWidth(),
        )
        FitDesiTextField(
            value = "Unavailable",
            onValueChange = {},
            label = "Question",
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Inputs - Light", showBackground = true, widthDp = 320)
@Composable
private fun InputFoundationLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") { InputFoundationPreviewContent() }
}

@Preview(name = "Inputs - Dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 320)
@Composable
private fun InputFoundationDarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") { InputFoundationPreviewContent() }
}
