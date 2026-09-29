package com.example.data.api

import com.example.BuildConfig
import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class DetailedGuide(
    val howToSetup: List<String>?,
    val howToPerform: List<String>?,
    val properTechnique: List<String>?,
    val thingsToAvoid: List<String>?
)

@JsonClass(generateAdapter = true)
data class ExerciseDBExercise(
    val id: String?,
    val name: String?,
    val category: String?,
    @param:Json(name = "bodyPart") val bodyPart: String?,
    val muscles: List<String>?,
    @param:Json(name = "muscle_groups") val muscleGroups: List<String>?,
    @param:Json(name = "target") val target: String?,
    val equipment: String?,
    val difficulty: String?,
    val instructions: List<String>?,
    @param:Json(name = "steps") val steps: List<String>?,
    @param:Json(name = "video_url") val videoUrl: String?,
    val video: String?,
    @param:Json(name = "gifUrl") val gifUrl: String?,
    val thumbnail: String?,
    val image: String?,
    val grip: String? = null,
    val mechanic: String? = null,
    val force: String? = null,
    @param:Json(name = "detailedGuide") val detailedGuide: DetailedGuide? = null
) {
    fun getSafeId(): Int = id?.toIntOrNull() ?: name?.hashCode() ?: 0
    fun getSafeName(): String = name ?: "Unknown Exercise"
    fun getSafeCategory(): String = category ?: bodyPart ?: "Other"
    fun getSafeMuscles(): List<String> = muscles ?: muscleGroups ?: target?.let { listOf(it) } ?: emptyList()
    fun getSafeEquipment(): String = equipment ?: "Bodyweight"
    fun getSafeDifficulty(): String = difficulty ?: "beginner"
    fun getSafeInstructions(): List<String> = instructions ?: steps ?: emptyList()
    fun getSafeVideoUrl(): String = videoUrl ?: video ?: ""
    fun getSafeThumbnail(): String = thumbnail ?: gifUrl ?: image ?: ""
}

class FlexibleListStringAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): List<String> {
        val list = mutableListOf<String>()
        if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                list.add(reader.nextString())
            }
            reader.endArray()
        } else if (reader.peek() == JsonReader.Token.STRING) {
            val str = reader.nextString()
            val items = if (str.contains("\n")) {
                str.split("\n")
            } else {
                str.split(",")
            }
            list.addAll(items.map { it.trim() }.filter { it.isNotEmpty() })
        } else {
            reader.skipValue()
        }
        return list
    }

    @ToJson
    fun toJson(writer: JsonWriter, value: List<String>?) {
        writer.beginArray()
        value?.forEach { writer.value(it) }
        writer.endArray()
    }
}

@JsonClass(generateAdapter = true)
data class ExerciseDBApiResponse(
    val status: String? = "",
    val data: List<ExerciseDBExercise>? = emptyList(),
    val message: String? = ""
)

interface ExerciseDBService {
    @GET("api/v1/exercises")
    suspend fun getExercises(@retrofit2.http.Query("limit") limit: Int = 1000): ExerciseDBApiResponse
}

object ExerciseDBApiClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val moshi = Moshi.Builder()
        .add(FlexibleListStringAdapter())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val service: ExerciseDBService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.FITDESI_BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ExerciseDBService::class.java)
    }
}
