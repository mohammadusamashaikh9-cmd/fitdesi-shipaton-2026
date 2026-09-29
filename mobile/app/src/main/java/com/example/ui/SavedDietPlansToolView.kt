package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedDietPlanMeal
import com.example.ai.planSourceDisplayLabel
import com.example.ai.planValidationDisplayLabel
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.viewmodel.SavedDietPlansUiState
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedDietPlansToolView(
    state: SavedDietPlansUiState,
    selectedPlanId: String?,
    onSelectPlan: (String) -> Unit,
    onBackToList: () -> Unit,
    onRetry: () -> Unit,
    onDeletePlan: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        SavedDietPlansUiState.Loading -> {
            SavedDietPlansLoading(modifier)
            return
        }
        is SavedDietPlansUiState.Error -> {
            SavedDietPlansError(state.message, onRetry, modifier)
            return
        }
        is SavedDietPlansUiState.Success -> Unit
    }
    val plans = (state as SavedDietPlansUiState.Success).plans
    val selectedPlan = plans.firstOrNull { it.planId == selectedPlanId }
    var pendingDelete by remember { mutableStateOf<GeneratedDietPlan?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete diet plan?") },
            text = { Text("This removes the saved plan from this device. Completed food logs are not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val plan = pendingDelete ?: return@TextButton
                        pendingDelete = null
                        onDeletePlan(plan.planId) { deleted ->
                            if (deleted) {
                                deleteError = null
                                onBackToList()
                            } else {
                                deleteError = "The plan could not be deleted. Please try again."
                            }
                        }
                    }
                ) {
                    Text("DELETE", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("CANCEL")
                }
            }
        )
    }

    if (selectedPlan != null) {
        SavedDietPlanDetail(
            plan = selectedPlan,
            deleteError = deleteError,
            onBack = onBackToList,
            onDelete = { pendingDelete = selectedPlan },
            modifier = modifier
        )
    } else {
        SavedDietPlanList(
            plans = plans,
            deleteError = deleteError,
            onSelectPlan = onSelectPlan,
            modifier = modifier
        )
    }
}

@Composable
private fun SavedDietPlansLoading(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("saved_diet_plans_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Loading saved diet plans…", fontFamily = InterFontFamily)
    }
}

@Composable
private fun SavedDietPlansError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("saved_diet_plans_error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "SAVED PLANS UNAVAILABLE",
            color = MaterialTheme.colorScheme.error,
            fontFamily = OswaldFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = InterFontFamily
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("TRY AGAIN", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SavedDietPlanList(
    plans: List<GeneratedDietPlan>,
    deleteError: String?,
    onSelectPlan: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (plans.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag("saved_diet_plans_empty"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.RestaurantMenu,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "NO SAVED DIET PLANS",
                fontFamily = OswaldFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Generate a Pakistani diet plan in AI Coach, then choose Save Diet Plan.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterFontFamily
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("saved_diet_plans_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (deleteError != null) {
            item {
                Text(
                    deleteError,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = InterFontFamily
                )
            }
        }
        items(plans, key = GeneratedDietPlan::planId) { plan ->
            Card(
                onClick = { onSelectPlan(plan.planId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("saved_diet_plan_${plan.planId}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        plan.title,
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                    Text(
                        "${plan.goal} • ${plan.mealCount} meals",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp
                    )
                    Text(
                        plan.calorieTarget?.let { "$it kcal target" } ?: "Calorie target not set",
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Created ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(plan.createdAt))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedDietPlanDetail(
    plan: GeneratedDietPlan,
    deleteError: String?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("saved_diet_plan_detail"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to saved diet plans")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plan.title,
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp
                    )
                    Text(
                        plan.goal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp
                    )
                    Text(
                        "Source: ${planSourceDisplayLabel(plan.sourceType.name)} · " +
                            "Validation: ${planValidationDisplayLabel(plan.validationStatus.name)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp
                    )
                    if (plan.validationFailures.isNotEmpty()) {
                        Text(
                            "Needs attention: ${plan.validationFailures.joinToString()}",
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = InterFontFamily,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item {
            DietTargetCard(plan)
        }
        plan.days.forEach { day ->
            item(key = day.dayName) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        day.dayName.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    day.meals.forEach { meal ->
                        SavedMealCard(meal)
                    }
                }
            }
        }
        item {
            Text(
                plan.hydrationReminder,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                plan.disclaimer,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterFontFamily,
                fontSize = 12.sp
            )
        }
        if (deleteError != null) {
            item {
                Text(
                    deleteError,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = InterFontFamily
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("delete_saved_diet_plan"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" DELETE SAVED PLAN", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DietTargetCard(plan: GeneratedDietPlan) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("DAILY TARGETS", fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(
                    plan.calorieTarget?.let { "$it kcal" },
                    plan.proteinTargetGrams?.let { "${it.toInt()}g protein" },
                    plan.carbsTargetGrams?.let { "${it.toInt()}g carbs" },
                    plan.fatTargetGrams?.let { "${it.toInt()}g fat" }
                ).ifEmpty { listOf("No exact calorie or macro targets were available") }.joinToString(" • "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterFontFamily
            )
        }
    }
}

@Composable
private fun SavedMealCard(meal: GeneratedDietPlanMeal) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                meal.label.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                meal.foodName,
                fontFamily = OswaldFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                meal.portionDescription.ifBlank { "${meal.portionMultiplier} × ${meal.storedServing}" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterFontFamily
            )
            val nutrition = listOfNotNull(
                meal.estimatedCalories?.let { "$it kcal" },
                meal.estimatedProteinGrams?.let { "${it.toInt()}g protein" },
                meal.estimatedCarbsGrams?.let { "${it.toInt()}g carbs" },
                meal.estimatedFatGrams?.let { "${it.toInt()}g fat" }
            )
            Text(
                if (nutrition.isEmpty()) {
                    "Nutrition totals unavailable for this stored serving."
                } else {
                    "Estimated: ${nutrition.joinToString(" • ")}"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = InterFontFamily,
                fontSize = 12.sp
            )
            meal.additionalFoods.forEach { component ->
                Text(
                    "With ${component.foodName}: ${component.portionDescription} · " +
                        (component.estimatedCalories?.let { "~$it kcal" } ?: "nutrition incomplete"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp
                )
            }
            if (meal.alternatives.isNotEmpty()) {
                Text(
                    "Alternatives: ${meal.alternatives.joinToString { alternative ->
                        if (alternative.portionDescription.isBlank()) alternative.foodName
                        else "${alternative.foodName} (${alternative.portionDescription})"
                    }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp
                )
            }
        }
    }
}
