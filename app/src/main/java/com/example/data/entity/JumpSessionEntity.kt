package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jump_sessions")
data class JumpSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val totalJumps: Int,
    val durationSeconds: Long,
    val avgJpm: Int,
    val maxStreak: Int,
    val isFromFile: Boolean = false,
    val fileName: String? = null,
    val notes: String? = null,
    val sensitivityUsed: Float = 0.5f
)
