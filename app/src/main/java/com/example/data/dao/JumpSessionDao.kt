package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.JumpSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JumpSessionDao {
    @Query("SELECT * FROM jump_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<JumpSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: JumpSessionEntity): Long

    @Query("DELETE FROM jump_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM jump_sessions")
    suspend fun clearAllSessions()

    @Query("SELECT * FROM jump_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): JumpSessionEntity?
}
