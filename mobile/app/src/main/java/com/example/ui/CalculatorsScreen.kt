package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.TrainerViewModel

/**
 * Legacy calculator entry point retained for compatibility. It delegates to the same calculator
 * views used by Tools so formulas, validation, and empty input states cannot drift apart.
 */
@Composable
fun CalculatorsScreen(
    @Suppress("UNUSED_PARAMETER") viewModel: TrainerViewModel,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableStateOf("1RM") }
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "CALCULATORS",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("1RM" to "One Rep Max", "BMR" to "BMR & Calories").forEach { (key, label) ->
                val selected = selectedSubTab == key
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) accentColor else Color.Transparent)
                        .clickable { selectedSubTab = key }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (selectedSubTab == "1RM") {
            OneRepMaxToolView(
                surfaceColor = MaterialTheme.colorScheme.surface,
                onSurfaceColor = MaterialTheme.colorScheme.onSurface,
                onSurfaceSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
                accentColor = accentColor
            )
        } else {
            BmrCalorieToolView(
                surfaceColor = MaterialTheme.colorScheme.surface,
                onSurfaceColor = MaterialTheme.colorScheme.onSurface,
                onSurfaceSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
                accentColor = accentColor
            )
        }
    }
}
