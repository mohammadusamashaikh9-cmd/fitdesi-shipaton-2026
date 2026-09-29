package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UserProfile
import com.example.viewmodel.TrainerViewModel

@Composable
fun OnboardingScreen(
    viewModel: TrainerViewModel,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(1) }
    
    // States for inputs
    var name by remember { mutableStateOf("") }
    var ageStr by remember { mutableStateOf("25") }
    var gender by remember { mutableStateOf("Male") }
    var heightStr by remember { mutableStateOf("175") }
    var weightStr by remember { mutableStateOf("70") }
    
    var weightRange by remember { mutableStateOf("61-70kg") }
    var dietPreference by remember { mutableStateOf("Vegetarian") }
    var workoutDays by remember { mutableStateOf(4) }
    var goal by remember { mutableStateOf("Gain Muscle") }
    var workoutType by remember { mutableStateOf("Both") }
    var mealsPerDay by remember { mutableStateOf(4) }
    var workoutExperience by remember { mutableStateOf("Intermediate") }
    
    var hasAllergies by remember { mutableStateOf(false) }
    var allergiesDetails by remember { mutableStateOf("") }
    var activityLevel by remember { mutableStateOf("Moderate") }

    val scrollState = rememberScrollState()

    // Error states
    var nameError by remember { mutableStateOf(false) }
    var ageError by remember { mutableStateOf(false) }
    var heightError by remember { mutableStateOf(false) }
    var weightError by remember { mutableStateOf(false) }
    var allergyError by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Step Indicator & Progress
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (step > 1) {
                        IconButton(
                            onClick = { step-- },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .size(40.dp)
                                .testTag("btn_back")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(40.dp))
                    }

                    Text(
                        text = "STEP $step OF 10",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )

                    Spacer(modifier = Modifier.width(40.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress Indicator
                LinearProgressIndicator(
                    progress = { step / 10f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Body: Dynamic Step UI Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center
            ) {
                when (step) {
                    1 -> {
                        // Personal Details
                        OnboardingHeader(title = "Tell us about yourself", subtitle = "Help us calculate your correct baseline index.")
                        
                        OutlinedTextField(
                            value = name,
                            onValueChange = { 
                                name = it
                                nameError = it.trim().isEmpty()
                            },
                            label = { Text("Name") },
                            isError = nameError,
                            modifier = Modifier.fillMaxWidth().testTag("input_name"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        if (nameError) {
                            Text("Name is required", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ageStr,
                                onValueChange = { ageStr = it.filter { c -> c.isDigit() } },
                                label = { Text("Age") },
                                modifier = Modifier.weight(1f).testTag("input_age"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            
                            // Gender selection
                            Row(
                                modifier = Modifier
                                    .weight(1.5f)
                                    .height(56.dp)
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf("Male", "Female").forEach { g ->
                                    val isSel = gender == g
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .clickable { gender = g }
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(g, color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = heightStr,
                                onValueChange = { heightStr = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Height (cm)") },
                                modifier = Modifier.weight(1f).testTag("input_height"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = weightStr,
                                onValueChange = { weightStr = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Weight (kg)") },
                                modifier = Modifier.weight(1f).testTag("input_weight"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                    2 -> {
                        // Select Weight Range
                        OnboardingHeader(title = "Select your weight range", subtitle = "This provides standardized metrics for calculations.")
                        val ranges = listOf(
                            "41-50kg", "51-60kg", "61-70kg", "71-80kg", "81-90kg", "91-100kg", "101-110kg", "111-120kg", "120kg+"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ranges.forEach { range ->
                                OnboardingSelectionRow(
                                    label = range,
                                    isSelected = weightRange == range,
                                    onClick = { weightRange = range }
                                )
                            }
                        }
                    }
                    3 -> {
                        // Diet Preference
                        OnboardingHeader(title = "Choose your diet", subtitle = "Personalize macronutrients with your food preferences.")
                        val diets = listOf("Eggitarian", "Vegetarian", "Non-Vegetarian", "Vegan", "Keto")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            diets.forEach { diet ->
                                OnboardingSelectionRow(
                                    label = diet,
                                    isSelected = dietPreference == diet,
                                    onClick = { dietPreference = diet }
                                )
                            }
                        }
                    }
                    4 -> {
                        // Availability is distinct from the number of sessions a generated plan schedules.
                        OnboardingHeader(title = "Available training days", subtitle = "How many days each week could you train?")
                        val frequencies = listOf(
                            3 to "3 Days a Week",
                            4 to "4 Days a Week",
                            5 to "5 Days a Week",
                            6 to "6 Days a Week",
                            7 to "7 Days a Week"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            frequencies.forEach { (days, label) ->
                                OnboardingSelectionRow(
                                    label = label,
                                    isSelected = workoutDays == days,
                                    onClick = { workoutDays = days }
                                )
                            }
                        }
                    }
                    5 -> {
                        // Goal
                        OnboardingHeader(title = "Choose your goal", subtitle = "This adjusts your daily energy surplus or deficit target.")
                        val goals = listOf("Gain Muscle", "Lose Fat", "Maintain Weight", "Build Strength", "Increase Endurance")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            goals.forEach { item ->
                                OnboardingSelectionRow(
                                    label = item,
                                    isSelected = goal == item,
                                    onClick = { goal = item }
                                )
                            }
                        }
                    }
                    6 -> {
                        // Workout Type
                        OnboardingHeader(title = "Workout location", subtitle = "Do you work out at the gym, home, or both?")
                        val locations = listOf("Gym", "Home", "Both")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            locations.forEach { type ->
                                OnboardingSelectionRow(
                                    label = type,
                                    isSelected = workoutType == type,
                                    onClick = { workoutType = type }
                                )
                            }
                        }
                    }
                    7 -> {
                        // Number of Meals per Day
                        OnboardingHeader(title = "Daily meal count", subtitle = "How many meals do you prefer to eat per day?")
                        val meals = listOf(
                            3 to "3 Meals",
                            4 to "4 Meals",
                            5 to "5 Meals",
                            6 to "6 Meals"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            meals.forEach { (m, label) ->
                                OnboardingSelectionRow(
                                    label = label,
                                    isSelected = mealsPerDay == m,
                                    onClick = { mealsPerDay = m }
                                )
                            }
                        }
                    }
                    8 -> {
                        // Workout Experience
                        OnboardingHeader(title = "Workout experience", subtitle = "What's your experience level with fitness?")
                        val experiences = listOf(
                            "Beginner <6 months",
                            "Intermediate 6-12 months",
                            "Advanced >1 year"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            experiences.forEach { exp ->
                                OnboardingSelectionRow(
                                    label = exp,
                                    isSelected = workoutExperience == exp,
                                    onClick = { workoutExperience = exp }
                                )
                            }
                        }
                    }
                    9 -> {
                        // Allergies
                        OnboardingHeader(title = "Any dietary allergies?", subtitle = "Let us know if you have any allergies.")
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (!hasAllergies) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                    .clickable { hasAllergies = false }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No", color = if (!hasAllergies) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (hasAllergies) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                    .clickable { hasAllergies = true }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Yes", color = if (hasAllergies) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (hasAllergies) {
                            OutlinedTextField(
                                value = allergiesDetails,
                                onValueChange = { 
                                    allergiesDetails = it
                                    allergyError = it.trim().isEmpty()
                                },
                                label = { Text("Allergy Details (e.g. Peanut, Gluten)") },
                                modifier = Modifier.fillMaxWidth().testTag("input_allergies"),
                                isError = allergyError,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            if (allergyError) {
                                Text("Please list details about your allergy", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                            }
                        }
                    }
                    10 -> {
                        // Activity Level
                        OnboardingHeader(title = "Activity level", subtitle = "Select your daily activity rate to fine-tune TDEE calculations.")
                        val activities = listOf(
                            "Sedentary" to "Sedentary (Little/no exercise)",
                            "Light" to "Light (Some walking and light routine activity)",
                            "Moderate" to "Moderate (Regular movement across work and daily life)",
                            "Active" to "Active (Highly active lifestyle or physical work)",
                            "Very Active" to "Very Active (Daily heavy physical labor/athletics)"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activities.forEach { (key, label) ->
                                OnboardingSelectionRow(
                                    label = label,
                                    isSelected = activityLevel == key,
                                    onClick = { activityLevel = key }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Footer: Next Button
            Button(
                onClick = {
                    // Validations before moving next
                    if (step == 1) {
                        nameError = name.trim().isEmpty()
                        val age = ageStr.toIntOrNull() ?: 0
                        ageError = age <= 0
                        val height = heightStr.toFloatOrNull() ?: 0f
                        heightError = height <= 0f
                        val weight = weightStr.toFloatOrNull() ?: 0f
                        weightError = weight <= 0f

                        if (!nameError && !ageError && !heightError && !weightError) {
                            step++
                        }
                    } else if (step == 9) {
                        if (hasAllergies && allergiesDetails.trim().isEmpty()) {
                            allergyError = true
                        } else {
                            allergyError = false
                            step++
                        }
                    } else if (step == 10) {
                        // Submit Onboarding!
                        val profile = UserProfile(
                            name = name.trim(),
                            age = ageStr.toIntOrNull() ?: 0,
                            gender = gender,
                            heightCm = heightStr.toFloatOrNull() ?: 0f,
                            weightKg = weightStr.toFloatOrNull() ?: 0f,
                            weightRange = weightRange,
                            dietPreference = dietPreference,
                            workoutDays = workoutDays,
                            goal = goal,
                            workoutType = workoutType,
                            mealsPerDay = mealsPerDay,
                            workoutExperience = workoutExperience,
                            hasAllergies = hasAllergies,
                            allergiesSpecified = true,
                            allergiesDetails = if (hasAllergies) allergiesDetails.trim() else "",
                            activityLevel = activityLevel,
                            isOnboardingCompleted = true
                        )
                        viewModel.saveProfile(profile)
                    } else {
                        step++
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_next"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (step == 10) "GET STARTED" else "CONTINUE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun OnboardingHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = Color.Gray,
            lineHeight = 18.sp
        )
    }
}

@Composable
fun OnboardingSelectionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
