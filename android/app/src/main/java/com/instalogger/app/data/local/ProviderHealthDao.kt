package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderHealthDao {
    @Query("SELECT * FROM provider_health ORDER BY providerId ASC")
    fun getAllFlow(): Flow<List<ProviderHealthEntity>>

    @Query("SELECT * FROM provider_health WHERE providerId = :providerId LIMIT 1")
    suspend fun getById(providerId: String): ProviderHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderHealthEntity)

    @Query("DELETE FROM provider_health")
    suspend fun clearAll()
}
