package com.example.exercise

import java.util.Locale

internal object CanonicalEquipmentCapabilities {
    private val fullGymEquipment = setOf(
        "assisted",
        "band",
        "barbell",
        "bodyweight",
        "cable",
        "chair",
        "dumbbell",
        "leverage machine",
        "medicine ball",
        "rope",
        "stability ball"
    )

    private val individualEquipmentOptions = setOf(
        "bodyweight",
        "dumbbells",
        "barbell",
        "barbell and rack",
        "cables",
        "machine",
        "resistance bands"
    )

    private val externalResistance = setOf(
        "band",
        "barbell",
        "cable",
        "dumbbell",
        "kettlebell",
        "leverage machine",
        "machine",
        "medicine ball"
    )

    fun availableEquipment(selectedEquipment: Collection<String>): Set<String> {
        val normalized = selectedEquipment.map(::normalizeLabel).filter(String::isNotBlank).toSet()
        if (normalized.any { it == "full gym" }) return fullGymEquipment

        return buildSet {
            if (normalized.any { it in individualEquipmentOptions }) add("bodyweight")
            normalized.forEach { label ->
                when (label) {
                    "bodyweight" -> add("bodyweight")
                    "dumbbells" -> add("dumbbell")
                    "barbell", "barbell and rack" -> add("barbell")
                    "cables" -> add("cable")
                    "machine" -> {
                        add("machine")
                        add("leverage machine")
                        add("assisted")
                    }
                    "resistance bands" -> add("band")
                }
            }
        }
    }

    fun isCompatible(requiredEquipment: Collection<String>, selectedEquipment: Collection<String>): Boolean {
        val required = requiredEquipment.map(::normalizeEquipment).filter(String::isNotBlank).toSet()
        if (required.isEmpty()) return false
        val available = availableEquipment(selectedEquipment)
        return required.all(available::contains)
    }

    fun compatibilityStrength(
        requiredEquipment: Collection<String>,
        selectedEquipment: Collection<String>
    ): Int {
        val explicitlyAvailable = selectedEquipment
            .flatMap { selected -> explicitCapabilities(normalizeLabel(selected)) }
            .toSet()
        return requiredEquipment
            .map(::normalizeEquipment)
            .distinct()
            .count(explicitlyAvailable::contains)
    }

    fun hasMeaningfulExternalResistance(equipment: Collection<String>): Boolean =
        equipment.map(::normalizeEquipment).any(externalResistance::contains)

    fun meaningfulEquipment(equipment: Collection<String>): String? =
        equipment.map(::normalizeEquipment)
            .firstOrNull { it.isNotBlank() && it != "bodyweight" && it != "none" }

    fun normalizeEquipment(value: String): String = when (normalizeLabel(value)) {
        "body weight", "bodyweight" -> "bodyweight"
        "dumbbell", "dumbbells" -> "dumbbell"
        "kettlebell", "kettlebells" -> "kettlebell"
        "cable", "cables" -> "cable"
        "resistance band", "resistance bands", "band", "bands" -> "band"
        "pull up bar", "pull-up bar" -> "pull-up bar"
        else -> normalizeLabel(value)
    }

    fun normalizeLabel(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun explicitCapabilities(label: String): Set<String> = when (label) {
        "full gym" -> fullGymEquipment
        "bodyweight" -> setOf("bodyweight")
        "dumbbells" -> setOf("dumbbell")
        "barbell", "barbell and rack" -> setOf("barbell")
        "cables" -> setOf("cable")
        "machine" -> setOf("machine", "leverage machine", "assisted")
        "resistance bands" -> setOf("band")
        else -> emptySet()
    }
}
