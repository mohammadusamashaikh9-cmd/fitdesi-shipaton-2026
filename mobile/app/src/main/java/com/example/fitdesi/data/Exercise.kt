package com.example.fitdesi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

object NullableStringOrListSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NullableStringOrList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return if (element is JsonArray) {
            element.joinToString("\n") { it.jsonPrimitive.content }
        } else {
            element.jsonPrimitive.content
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            // Nulls are handled by serialization framework but this is a fallback
            val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            if (jsonEncoder != null) {
                jsonEncoder.encodeJsonElement(JsonNull)
            } else {
                encoder.encodeString("")
            }
        } else {
            encoder.encodeString(value)
        }
    }
}

@Serializable
data class Instructions(
    @Serializable(with = NullableStringOrListSerializer::class)
    val en: String? = null,
    @Serializable(with = NullableStringOrListSerializer::class)
    val tr: String? = null
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val category: String? = null,
    @SerialName("body_part")
    val bodyPart: String? = null,
    val equipment: String? = null,
    val instructions: Instructions? = null,
    @SerialName("muscle_group")
    val muscleGroup: String? = null,
    @SerialName("secondary_muscles")
    val secondaryMuscles: List<String>? = null,
    val target: String? = null,
    val image: String? = null,
    @SerialName("gif_url")
    val gifUrl: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)
