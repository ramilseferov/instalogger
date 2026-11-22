package com.instalogger.app.data.repository

import com.instalogger.app.data.remote.ApiService
import com.instalogger.app.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TargetRepository(private var apiService: ApiService) {

    fun updateApiService(newService: ApiService) {
        apiService = newService
    }

    suspend fun getTargets(): Result<List<TargetDto>> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.getTargets()
            Result.success(res.targets)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTarget(id: Long): Result<TargetDto?> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.getTarget(id)
            Result.success(res.target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addTarget(username: String, turbo: Boolean): Result<TargetDto?> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.addTarget(CreateTargetRequest(username = username, isTurbo = turbo))
            Result.success(res.target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteTarget(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.deleteTarget(id)
            Result.success(res.status == "ok")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleTurbo(id: Long, turbo: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.toggleTurbo(id, mapOf("turbo" to turbo))
            Result.success(res.isTurbo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkTargetNow(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.checkTargetNow(id)
            Result.success(res.status == "ok")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTargetLogs(id: Long): Result<List<ChangeLogDto>> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.getTargetLogs(id)
            Result.success(res.logs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTargetAvatars(id: Long): Result<List<AvatarItemDto>> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.getTargetAvatars(id)
            Result.success(res.avatars)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSettings(): Result<SettingsData> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.getSettings()
            Result.success(res.settings)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveSessionCookie(cookie: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.updateSettings(UpdateSettingsRequest(sessionCookie = cookie))
            Result.success(res.status == "ok")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
