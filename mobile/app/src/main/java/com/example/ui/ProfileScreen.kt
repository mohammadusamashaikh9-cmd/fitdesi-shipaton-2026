package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.AutomaticCalorieInput
import com.example.data.UserProfile
import com.example.viewmodel.TrainerViewModel
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(
    viewModel: TrainerViewModel,
    membershipValue: String,
    onPlanAndMembershipClick: () -> Unit,
    accountValue: String,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()

    var activeDialogField by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val hasBmiInputs = profile.hasBmiInputs()
    val bmi = profile.getBmi()
    val bmiStatus = profile.getBmiStatus()
    val bmr = profile.calculateBmr()
    val calorieCalculation = profile.calorieCalculation()
    val dailyCalories = profile.calculateDailyCalories()
    val automaticCalorieMissingInputs = profile.automaticCalorieMissingInputs()
    val automaticCalorieGuidance = automaticCalorieReadinessGuidance(automaticCalorieMissingInputs)
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(200),
        label = "profile_content_alpha"
    )
    val contentOffset by animateFloatAsState(
        targetValue = if (contentVisible) 0f else 10f,
        animationSpec = tween(200),
        label = "profile_content_offset"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .graphicsLayer {
                alpha = contentAlpha
                translationY = contentOffset
            }
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProfileHero(
            profile = profile,
            onEdit = { activeDialogField = "personal" }
        )

        if (!profile.hasCompletePersonalDetails()) {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("profile_setup_prompt"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Complete your profile",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Add your real details to unlock accurate FitDesi calculations and coaching context.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    TextButton(onClick = { activeDialogField = "personal" }) {
                        Text("Set up profile")
                    }
                }
            }
        }

        Text(
            text = "MEMBERSHIP",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        ProfileMembershipCard(
            membershipValue = membershipValue,
            onPlanAndMembershipClick = onPlanAndMembershipClick
        )

        Text(
            text = "Your metrics",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Top Summary Card (Like FITMUSK)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // A compact two-by-two deck remains readable at 320 dp and large text scales.
                ProfileEqualHeightRow(horizontalSpacing = 10.dp) {
                    BiometricInfoItem(
                        icon = Icons.Default.Cake,
                        label = "AGE",
                        value = if (profile.age > 0) "${profile.age} yrs" else "Not set",
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    BiometricInfoItem(
                        icon = Icons.Default.Height,
                        label = "HEIGHT",
                        value = if (profile.heightCm > 0f) "${profile.heightCm.roundToInt()} cm" else "Not set",
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                ProfileEqualHeightRow(horizontalSpacing = 10.dp) {
                    BiometricInfoItem(
                        icon = Icons.Default.MonitorWeight,
                        label = "WEIGHT",
                        value = if (profile.weightKg > 0f) "${profile.weightKg.roundToInt()} kg" else "Not set",
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    ProfileGoalBiometricItem(
                        goal = profile.goal,
                        onEditField = { activeDialogField = it },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f), thickness = 1.dp)

                // BMI Display Row
                val statusColor = when (bmiStatus) {
                    "Underweight" -> Color(0xFFFFD700) // Gold/Yellow
                    "Healthy" -> Color(0xFF4CAF50)     // Green
                    "Overweight" -> Color(0xFFFF9800)  // Orange
                    "Obese" -> Color(0xFFF44336)
                    else -> Color.Gray
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("BODY MASS INDEX (BMI)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(
                            text = if (hasBmiInputs) String.format("%.1f", bmi) else "--",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (hasBmiInputs) bmiStatus.uppercase() else "ADD HEIGHT & WEIGHT",
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // BMR & Target Calories calculated baseline row
                ProfileEqualHeightRow(horizontalSpacing = 8.dp) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("BMR BASELINE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(
                            if (bmr > 0f) "${bmr.roundToInt()} kcal" else "Complete profile",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("MAINTENANCE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(
                            calorieCalculation?.maintenanceCalories?.roundToInt()?.let { "$it kcal" }
                                ?: automaticCalorieGuidance,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DAILY CALORIE GOAL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(
                            if (dailyCalories > 0) "$dailyCalories kcal" else automaticCalorieGuidance,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val calorieModeLabel = when {
                            profile.customCalorieGoal > 0 -> "Manual"
                            calorieCalculation != null -> "%+.1f%% Auto".format(calorieCalculation.goalAdjustmentPercent * 100)
                            else -> null
                        }
                        calorieModeLabel?.let { label ->
                            Text(
                                label,
                                color = Color.Gray,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // App Theme Section
        Text(
            text = "PREFERENCES",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Appearance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(profile.theme, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("System", "Light", "Dark").forEach { option ->
                        FilterChip(
                            selected = profile.theme == option,
                            onClick = { viewModel.saveTheme(option) },
                            label = { Text(option, maxLines = 1) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        )
                    }
                }
            }
        }

        // Grouped profile sections retain the existing edit fields and persistence callbacks.
        Text(
            text = "PERSONAL TARGETS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsListItem(
                    icon = Icons.Default.Star,
                    title = "Daily Calorie Goal",
                    value = when {
                        profile.customCalorieGoal > 0 -> "${profile.customCalorieGoal} kcal (Custom)"
                        dailyCalories > 0 -> "$dailyCalories kcal (Auto)"
                        else -> automaticCalorieGuidance
                    },
                    onClick = { activeDialogField = "custom_calorie_goal" }
                )
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

                SettingsListItem(
                    icon = Icons.Default.Phone,
                    title = "Mobile Number",
                    value = profile.mobileNumber.ifBlank { "Not set" },
                    onClick = { activeDialogField = "mobile" }
                )
            }
        }

        Text(
            text = "FITNESS PROFILE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        ProfileSummaryGrid(
            profile = profile,
            onEditField = { activeDialogField = it }
        )

        Text(
            text = "ACCOUNT & APP",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        ProfileAccountAndAppCard(
            personalDetailsValue = if (profile.hasCompletePersonalDetails()) {
                "Profile complete"
            } else {
                "Complete profile"
            },
            accountValue = accountValue,
            versionName = BuildConfig.VERSION_NAME,
            onPersonalDetailsClick = { activeDialogField = "personal" },
            onAccountClick = onAccountClick
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Dynamic Edit Dialog Selector
    activeDialogField?.let { field ->
        EditFieldDialog(
            field = field,
            profile = profile,
            onDismiss = { activeDialogField = null },
            onSave = { updatedProfile ->
                viewModel.saveProfile(updatedProfile)
                activeDialogField = null
                Toast.makeText(context, "Profile updated successfully! 💪", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

private data class ProfileSummaryItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val value: String,
    val field: String
)

@Composable
internal fun ProfileEqualHeightRow(
    horizontalSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        content = content
    )
}

@Composable
private fun ProfileSummaryGrid(
    profile: UserProfile,
    onEditField: (String) -> Unit
) {
    val items = listOf(
        ProfileSummaryItem(Icons.AutoMirrored.Filled.DirectionsRun, "Activity", profile.activityLevel.ifBlank { "Not set" }, "activity"),
        ProfileSummaryItem(Icons.Default.FitnessCenter, "Experience", profile.workoutExperience.ifBlank { "Not set" }, "experience"),
        ProfileSummaryItem(Icons.Default.DateRange, "Training days", if (profile.workoutDays > 0) "${profile.workoutDays} days/week" else "Not set", "workout_days"),
        ProfileSummaryItem(Icons.Default.Home, "Workout type", profile.workoutType.ifBlank { "Not set" }, "workout_type"),
        ProfileSummaryItem(Icons.Default.Restaurant, "Diet", profile.normalizedDietPreference().displayName, "diet"),
        ProfileSummaryItem(Icons.Default.RestaurantMenu, "Meals", if (profile.mealsPerDay > 0) "${profile.mealsPerDay} per day" else "Not set", "meals"),
        ProfileSummaryItem(
            Icons.Default.Warning,
            "Allergies",
            when {
                !profile.allergiesSpecified -> "Not set"
                profile.hasAllergies -> profile.allergiesDetails.ifBlank { "Details needed" }
                else -> "None recorded"
            },
            "allergies"
        ),
        ProfileSummaryItem(Icons.Default.LineWeight, "Weight range", profile.weightRange.ifBlank { "Not set" }, "weight_range")
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            ProfileEqualHeightRow(horizontalSpacing = 10.dp) {
                rowItems.forEach { item ->
                    ProfileSummaryTile(
                        item = item,
                        onClick = { onEditField(item.field) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSummaryTile(
    item: ProfileSummaryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 112.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(item.label.uppercase(), fontSize = 9.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.value, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
        }
    }
}

@Composable
private fun ProfileHero(
    profile: UserProfile,
    onEdit: () -> Unit
) {
    val initials = profile.name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "FD" }
    val summary = listOfNotNull(
        profile.goal.takeIf { it.isNotBlank() },
        profile.workoutExperience.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { "Complete your details for personalised FitDesi guidance" }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("profile_hero"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(4.dp, MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = profile.name.ifBlank { "Your FitDesi profile" },
                        fontSize = 24.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    Text(
                        text = summary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
            }
            FilledTonalButton(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("profile_edit")
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Edit profile", maxLines = 1, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BiometricInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    highlighted: Boolean = false,
    supportingText: String? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val container = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    val accent = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.primary
    val itemModifier = if (onClick != null) {
        modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
    } else {
        modifier
    }
    Column(
        modifier = itemModifier
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            supportingText?.let { text ->
                Text(
                    text = text,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1
                )
            }
        }
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 0.8.sp)
        Text(
            text = value,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}

@Composable
internal fun ProfileGoalBiometricItem(
    goal: String,
    onEditField: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BiometricInfoItem(
        icon = Icons.Default.Flag,
        label = "GOAL",
        value = goal.ifBlank { "Not set" },
        highlighted = true,
        supportingText = "Tap to edit",
        onClickLabel = "Edit fitness goal",
        onClick = { onEditField("goal") },
        modifier = modifier.testTag("profile_goal_edit")
    )
}

internal fun automaticCalorieReadinessGuidance(
    missingInputs: List<AutomaticCalorieInput>
): String {
    val labels = missingInputs.map { input ->
        when (input) {
            AutomaticCalorieInput.AGE -> "age (18-100)"
            AutomaticCalorieInput.GENDER -> "gender"
            AutomaticCalorieInput.HEIGHT -> "height (120-230 cm)"
            AutomaticCalorieInput.WEIGHT -> "weight (30-350 kg)"
            AutomaticCalorieInput.ACTIVITY_LEVEL -> "activity level"
            AutomaticCalorieInput.FITNESS_GOAL -> "fitness goal"
        }
    }
    val missing = when (labels.size) {
        0 -> return "Automatic targets available"
        1 -> labels.single()
        2 -> labels.joinToString(" and ")
        else -> labels.dropLast(1).joinToString(", ") + ", and " + labels.last()
    }
    return "Set $missing"
}

@Composable
fun VerticalDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(Color.Gray.copy(alpha = 0.3f))
    )
}

@Composable
fun SettingsListItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
            Text(
                text = value,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun ProfileMembershipCard(
    membershipValue: String,
    onPlanAndMembershipClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        SettingsListItem(
            icon = Icons.Default.Star,
            title = "Plan & membership",
            value = membershipValue,
            onClick = onPlanAndMembershipClick,
            modifier = Modifier.testTag("profile_plan_and_membership")
        )
    }
}

@Composable
internal fun ProfileAccountAndAppCard(
    personalDetailsValue: String,
    accountValue: String,
    versionName: String,
    onPersonalDetailsClick: () -> Unit,
    onAccountClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        SettingsListItem(
            icon = Icons.Default.Edit,
            title = "Personal details",
            value = personalDetailsValue,
            onClick = onPersonalDetailsClick
        )
        HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
        SettingsListItem(
            icon = Icons.Default.Lock,
            title = "Account & security",
            value = accountValue,
            onClick = onAccountClick,
            modifier = Modifier.testTag("profile_account_security")
        )
        HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "FitDesi AI",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Version $versionName",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("app_version_label")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFieldDialog(
    field: String,
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Edit ${field.replace("_", " ").uppercase()}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Render matching editor body based on field
                var tempProfile by remember { mutableStateOf(profile) }

                when (field) {
                    "custom_calorie_goal" -> {
                        val automaticProfile = profile.copy(customCalorieGoal = 0)
                        val autoCalculatedGoal = automaticProfile.calculateDailyCalories()
                        val automaticGuidance = automaticCalorieReadinessGuidance(
                            automaticProfile.automaticCalorieMissingInputs()
                        )
                        var goalStr by remember {
                            mutableStateOf(
                                when {
                                    profile.customCalorieGoal > 0 -> profile.customCalorieGoal.toString()
                                    autoCalculatedGoal > 0 -> autoCalculatedGoal.toString()
                                    else -> ""
                                }
                            )
                        }
                        var useAuto by remember { mutableStateOf(profile.customCalorieGoal <= 0) }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { useAuto = !useAuto }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Auto-Calculate Goal", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Automatically calculate calorie goal based on your profile", color = Color.Gray, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = useAuto,
                                    onCheckedChange = { useAuto = it }
                                )
                            }

                            if (!useAuto) {
                                OutlinedTextField(
                                    value = goalStr,
                                    onValueChange = { goalStr = it.filter { c -> c.isDigit() } },
                                    label = { Text("Custom Daily Calorie Goal (kcal)") },
                                    modifier = Modifier.fillMaxWidth().testTag("edit_calorie_goal_input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        focusedContainerColor = MaterialTheme.colorScheme.background,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                                    )
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = if (autoCalculatedGoal > 0) {
                                            "Current calculated goal: $autoCalculatedGoal kcal based on height, weight, activity, and goals."
                                        } else {
                                            "$automaticGuidance to calculate calorie and macro targets automatically."
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        tempProfile = tempProfile.copy(customCalorieGoal = if (useAuto) 0 else (goalStr.toIntOrNull() ?: 0))
                    }

                    "mobile" -> {
                        var num by remember { mutableStateOf(profile.mobileNumber) }
                        OutlinedTextField(
                            value = num,
                            onValueChange = { num = it },
                            placeholder = { Text("Enter Mobile Number") },
                            modifier = Modifier.fillMaxWidth().testTag("edit_mobile_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background
                            )
                        )
                        tempProfile = tempProfile.copy(mobileNumber = num)
                    }
                    "diet" -> {
                        val options = listOf("Eggitarian", "Vegetarian", "Non-Vegetarian", "Vegan", "Keto")
                        var diet by remember { mutableStateOf(profile.normalizedDietPreference().displayName.takeUnless { it == "Not set" }.orEmpty()) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (diet == item) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)
                                        .clickable { diet = item }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    if (diet == item) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(dietPreference = diet)
                    }
                    "workout_days" -> {
                        val options = listOf(3, 4, 5, 6, 7)
                        var days by remember { mutableStateOf(profile.workoutDays) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            options.forEach { num ->
                                val isSelected = days == num
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                                        .clickable { days = num }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = num.toString(),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(workoutDays = days)
                    }
                    "goal" -> {
                        val options = listOf("Gain Muscle", "Lose Fat", "Maintain Weight", "Build Strength", "Increase Endurance")
                        var goal by remember { mutableStateOf(profile.goal) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (goal == item) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)
                                        .clickable { goal = item }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    if (goal == item) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(goal = goal)
                    }
                    "workout_type" -> {
                        val options = listOf("Gym", "Home", "Both")
                        var type by remember { mutableStateOf(profile.workoutType) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            options.forEach { item ->
                                val isSel = type == item
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                                        .clickable { type = item }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = item,
                                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(workoutType = type)
                    }
                    "meals" -> {
                        val options = listOf(3, 4, 5, 6)
                        var meals by remember { mutableStateOf(profile.mealsPerDay) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            options.forEach { num ->
                                val isSelected = meals == num
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                                        .clickable { meals = num }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = num.toString(),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(mealsPerDay = meals)
                    }
                    "experience" -> {
                        val options = listOf("Beginner <6 months", "Intermediate 6-12 months", "Advanced >1 year")
                        var exp by remember { mutableStateOf(profile.workoutExperience) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (exp == item) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)
                                        .clickable { exp = item }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    if (exp == item) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(workoutExperience = exp)
                    }
                    "allergies" -> {
                        var has by remember { mutableStateOf(profile.hasAllergies) }
                        var details by remember { mutableStateOf(profile.allergiesDetails) }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (!has) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                                        .clickable { has = false }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No",
                                        color = if (!has) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (has) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                                        .clickable { has = true }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Yes",
                                        color = if (has) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (has) {
                                OutlinedTextField(
                                    value = details,
                                    onValueChange = { details = it },
                                    placeholder = { Text("Details (e.g., Peanuts)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        focusedContainerColor = MaterialTheme.colorScheme.background,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                                    )
                                )
                            }
                        }
                        tempProfile = tempProfile.copy(
                            hasAllergies = has,
                            allergiesSpecified = true,
                            allergiesDetails = if (has) details else ""
                        )
                    }
                    "weight_range" -> {
                        val options = listOf("41-50kg", "51-60kg", "61-70kg", "71-80kg", "81-90kg", "91-100kg", "101-110kg", "111-120kg", "120kg+")
                        var range by remember { mutableStateOf(profile.weightRange) }
                        Column(
                            modifier = Modifier
                                .height(220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            options.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (range == item) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)
                                        .clickable { range = item }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    if (range == item) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(weightRange = range)
                    }
                    "activity" -> {
                        val options = listOf("Sedentary", "Light", "Moderate", "Active", "Very Active")
                        var act by remember { mutableStateOf(profile.activityLevel) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (act == item) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background)
                                        .clickable { act = item }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    if (act == item) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        tempProfile = tempProfile.copy(activityLevel = act)
                    }
                    "personal" -> {
                        var nm by remember { mutableStateOf(profile.name) }
                        var age by remember { mutableStateOf(profile.age.takeIf { it > 0 }?.toString().orEmpty()) }
                        var gend by remember { mutableStateOf(profile.gender) }
                        var ht by remember { mutableStateOf(profile.heightCm.takeIf { it > 0f }?.toString().orEmpty()) }
                        var wt by remember { mutableStateOf(profile.weightKg.takeIf { it > 0f }?.toString().orEmpty()) }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = nm,
                                onValueChange = { nm = it },
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    focusedContainerColor = MaterialTheme.colorScheme.background,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.background
                                )
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = age,
                                    onValueChange = { age = it.filter { c -> c.isDigit() } },
                                    label = { Text("Age") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        focusedContainerColor = MaterialTheme.colorScheme.background,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                                    )
                                )

                                Row(
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .height(56.dp)
                                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), RoundedCornerShape(4.dp))
                                        .padding(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf("Male", "Female").forEach { g ->
                                        val isSelected = gend == g
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                .clickable { gend = g }
                                                .padding(horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = g,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = ht,
                                    onValueChange = { ht = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Height (cm)") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        focusedContainerColor = MaterialTheme.colorScheme.background,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                                    )
                                )

                                OutlinedTextField(
                                    value = wt,
                                    onValueChange = { wt = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Weight (kg)") },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        focusedContainerColor = MaterialTheme.colorScheme.background,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                                    )
                                )
                            }
                        }

                        tempProfile = tempProfile.copy(
                            name = nm.trim(),
                            age = age.toIntOrNull() ?: profile.age,
                            gender = gend,
                            heightCm = ht.toFloatOrNull() ?: profile.heightCm,
                            weightKg = wt.toFloatOrNull() ?: profile.weightKg
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(tempProfile) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
