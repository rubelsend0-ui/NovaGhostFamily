package com.novaghost.family.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "inbox")
data class InboxEntity(@PrimaryKey val id: String, val message: String, val receivedAt: Long, val source: String)

@Entity(tableName = "usage_cache")
data class UsageCacheEntity(@PrimaryKey val cacheKey: String, val json: String, val updatedAt: Long)

@Dao
interface NovaDao {
    @Query("SELECT * FROM inbox ORDER BY receivedAt DESC LIMIT 200")
    fun inbox(): Flow<List<InboxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: InboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUsage(e: UsageCacheEntity)

    @Query("SELECT * FROM usage_cache ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestUsage(): UsageCacheEntity?
}

@Database(entities = [InboxEntity::class, UsageCacheEntity::class], version = 1, exportSchema = false)
abstract class NovaDb : RoomDatabase() {
    abstract fun dao(): NovaDao
}
