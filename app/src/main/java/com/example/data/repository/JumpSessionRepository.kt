package com.example.data.repository

import com.example.data.dao.JumpSessionDao
import com.example.data.entity.JumpSessionEntity
import kotlinx.coroutines.flow.Flow

class JumpSessionRepository(private val dao: JumpSessionDao) {
    val allSessions: Flow<List<JumpSessionEntity>> = dao.getAllSessions()

    suspend fun insertSession(session: JumpSessionEntity): Long {
        return dao.insertSession(session)
    }

    suspend fun deleteSession(id: Long) {
        dao.deleteSession(id)
    }

    suspend fun clearAllSessions() {
        dao.clearAllSessions()
    }

    suspend fun getSessionById(id: Long): JumpSessionEntity? {
        return dao.getSessionById(id)
    }
}
