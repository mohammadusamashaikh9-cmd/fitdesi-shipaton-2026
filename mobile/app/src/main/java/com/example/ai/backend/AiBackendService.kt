package com.example.ai.backend

interface AiBackendService {
    suspend fun authSession(idToken: String): BackendSessionResult
    suspend fun health(): BackendResult<HealthResponseDto>
    suspend fun coach(
        idToken: String,
        idempotencyKey: String,
        request: CoachRequestDto
    ): BackendResult<CoachResponseDto>
    suspend fun workoutPlan(request: WorkoutPlanRequestDto): BackendResult<WorkoutPlanResponseDto>
    suspend fun foodAnalyze(request: FoodAnalyzeRequestDto): BackendResult<FoodAnalyzeResponseDto>
    suspend fun dietPlan(request: DietPlanRequestDto): BackendResult<DietPlanResponseDto>
    suspend fun yogaPlan(request: YogaPlanRequestDto): BackendResult<YogaPlanResponseDto>
    suspend fun progressReview(request: ProgressReviewRequestDto): BackendResult<ProgressReviewResponseDto>
}

interface RemoteAiConsentService {
    suspend fun consentState(idToken: String): BackendResult<RemoteAiConsentState>
    suspend fun updateStandardConsent(
        idToken: String,
        granted: Boolean,
        noticeVersion: String? = null
    ): BackendResult<Unit>
    suspend fun deleteConsent(idToken: String): BackendResult<Unit>
}

interface FitDesiBackendService : AiBackendService, RemoteAiConsentService
