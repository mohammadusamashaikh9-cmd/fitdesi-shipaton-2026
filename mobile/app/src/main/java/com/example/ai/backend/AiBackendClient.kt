package com.example.ai.backend

import com.example.BuildConfig

object AiBackendClient {
    val service: FitDesiBackendService by lazy {
        RetrofitAiBackendService.create(BuildConfig.FITDESI_BACKEND_BASE_URL)
    }
}
