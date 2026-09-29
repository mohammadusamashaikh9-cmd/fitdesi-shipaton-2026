package com.example.domain

enum class DietaryPreference {
    EGGITARIAN,
    VEGETARIAN,
    VEGAN,
    NON_VEGETARIAN,
    KETO,
    UNRESTRICTED,
    MISSING;

    val displayName: String
        get() = when (this) {
            EGGITARIAN -> "Eggitarian"
            VEGETARIAN -> "Vegetarian"
            VEGAN -> "Vegan"
            NON_VEGETARIAN -> "Non-Vegetarian"
            KETO -> "Keto"
            UNRESTRICTED -> "No preference"
            MISSING -> "Not set"
        }

    companion object {
        fun from(value: String?): DietaryPreference {
            val normalized = value.orEmpty().lowercase().replace(Regex("[^a-z]+"), "")
            return when (normalized) {
                "eggitarian", "eggetarian", "ovovegetarian" -> EGGITARIAN
                "vegetarian" -> VEGETARIAN
                "vegan" -> VEGAN
                "nonvegetarian", "nonveg" -> NON_VEGETARIAN
                "keto", "ketogenic" -> KETO
                "none", "nopreference", "unrestricted" -> UNRESTRICTED
                else -> MISSING
            }
        }
    }
}
