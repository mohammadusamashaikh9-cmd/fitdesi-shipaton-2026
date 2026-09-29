package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.FitDesiSpacing

@Composable
fun FitDesiBrandLockup(
    modifier: Modifier = Modifier,
    markWidth: Dp = 44.dp,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "FitDesi"
        },
        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_fitdesi_mark),
            contentDescription = null,
            modifier = Modifier.size(markWidth),
        )
        Text(
            text = "FitDesi",
            color = contentColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
