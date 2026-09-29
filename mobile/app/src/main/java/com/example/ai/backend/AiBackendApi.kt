package com.example.ai.backend

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

internal interface AiBackendApi {
    @GET("api/auth/session")
    suspend fun authSession(
        @Header("Authorization") authorization: String
    ): Response<BackendSessionSuccessEnvelope>

    @GET("api/privacy/consent")
    suspend fun consentState(
        @Header("Authorization") authorization: String
    ): Response<RemoteAiConsentSuccessEnvelope>

    @PUT("api/privacy/consent")
    suspend fun updateConsent(
        @Header("Authorization") authorization: String,
        @Body request: RemoteAiConsentMutationDto
    ): Response<Unit>

    @DELETE("api/privacy/consent")
    suspend fun deleteConsent(
        @Header("Authorization") authorization: String
    ): Response<Unit>

    @GET("api/health")
    suspend fun health(): Response<BackendSuccessEnvelope<HealthResponseDto>>

    @POST("api/ai/coach")
    suspend fun coach(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CoachRequestDto
    ): Response<BackendSuccessEnvelope<CoachResponseDto>>

    @POST("api/ai/workout-plan")
    suspend fun workoutPlan(
        @Body request: WorkoutPlanRequestDto
    ): Response<BackendSuccessEnvelope<WorkoutPlanResponseDto>>

    @POST("api/ai/food-analyze")
    suspend fun foodAnalyze(
        @Body request: FoodAnalyzeRequestDto
    ): Response<BackendSuccessEnvelope<FoodAnalyzeResponseDto>>

    @POST("api/ai/diet-plan")
    suspend fun dietPlan(
        @Body request: DietPlanRequestDto
    ): Response<BackendSuccessEnvelope<DietPlanResponseDto>>

    @POST("api/ai/yoga-plan")
    suspend fun yogaPlan(
        @Body request: YogaPlanRequestDto
    ): Response<BackendSuccessEnvelope<YogaPlanResponseDto>>

    @POST("api/ai/progress-review")
    suspend fun progressReview(
        @Body request: ProgressReviewRequestDto
    ): Response<BackendSuccessEnvelope<ProgressReviewResponseDto>>
}
