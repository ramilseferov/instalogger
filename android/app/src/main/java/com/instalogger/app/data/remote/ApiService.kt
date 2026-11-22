package com.instalogger.app.data.remote

import com.instalogger.app.data.remote.dto.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface ApiService {
    @GET("api/targets")
    suspend fun getTargets(): TargetsResponse

    @GET("api/targets/{id}")
    suspend fun getTarget(@Path("id") id: Long): TargetResponse

    @POST("api/targets")
    suspend fun addTarget(@Body request: CreateTargetRequest): TargetResponse

    @DELETE("api/targets/{id}")
    suspend fun deleteTarget(@Path("id") id: Long): GenericStatusResponse

    @POST("api/targets/{id}/turbo")
    suspend fun toggleTurbo(
        @Path("id") id: Long,
        @Body request: Map<String, Boolean>
    ): TurboResponse

    @POST("api/targets/{id}/check-now")
    suspend fun checkTargetNow(@Path("id") id: Long): GenericStatusResponse

    @GET("api/targets/{id}/logs")
    suspend fun getTargetLogs(@Path("id") id: Long): LogsResponse

    @GET("api/targets/{id}/avatars")
    suspend fun getTargetAvatars(@Path("id") id: Long): AvatarsResponse

    @GET("api/settings")
    suspend fun getSettings(): SettingsResponse

    @POST("api/settings")
    suspend fun updateSettings(@Body request: UpdateSettingsRequest): GenericStatusResponse

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:3000/"

        fun create(baseUrl: String = DEFAULT_BASE_URL): ApiService {
            val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            return Retrofit.Builder()
                .baseUrl(formattedUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
