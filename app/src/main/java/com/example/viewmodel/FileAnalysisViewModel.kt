package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioFileProcessor
import com.example.audio.FileAnalysisResult
import com.example.data.database.AppDatabase
import com.example.data.entity.JumpSessionEntity
import com.example.data.repository.JumpSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

sealed class FileAnalysisUiState {
    object Idle : FileAnalysisUiState()
    object Processing : FileAnalysisUiState()
    data class Success(
        val result: FileAnalysisResult,
        val adjustedJumps: Int,
        val isSaved: Boolean = false
    ) : FileAnalysisUiState()
    data class Error(val message: String) : FileAnalysisUiState()
}

class FileAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: JumpSessionRepository
    private val audioProcessor = AudioFileProcessor(application)

    private val _uiState = MutableStateFlow<FileAnalysisUiState>(FileAnalysisUiState.Idle)
    val uiState: StateFlow<FileAnalysisUiState> = _uiState.asStateFlow()

    init {
        val dao = AppDatabase.getDatabase(application).jumpSessionDao()
        repository = JumpSessionRepository(dao)
    }

    fun processAudioFile(fileUri: Uri, sensitivity: Float = 0.5f) {
        _uiState.value = FileAnalysisUiState.Processing
        viewModelScope.launch {
            val result = audioProcessor.analyzeAudioFile(
                fileUri = fileUri,
                sensitivity = sensitivity
            )

            result.onSuccess { analysisResult ->
                _uiState.value = FileAnalysisUiState.Success(
                    result = analysisResult,
                    adjustedJumps = analysisResult.totalJumps
                )
            }.onFailure { exception ->
                _uiState.value = FileAnalysisUiState.Error(
                    message = exception.localizedMessage ?: "Failed to analyze audio file."
                )
            }
        }
    }

    fun adjustCount(delta: Int) {
        val currentState = _uiState.value
        if (currentState is FileAnalysisUiState.Success) {
            val newCount = max(0, currentState.adjustedJumps + delta)
            _uiState.value = currentState.copy(adjustedJumps = newCount)
        }
    }

    fun saveFileSession(notes: String? = null) {
        val currentState = _uiState.value
        if (currentState is FileAnalysisUiState.Success) {
            viewModelScope.launch {
                val res = currentState.result
                val finalJumps = currentState.adjustedJumps
                val durationSec = res.durationSeconds
                val avgJpm = if (durationSec > 0) ((finalJumps * 60L) / durationSec).toInt() else 0

                val session = JumpSessionEntity(
                    totalJumps = finalJumps,
                    durationSeconds = durationSec,
                    avgJpm = avgJpm,
                    maxStreak = res.maxStreak,
                    isFromFile = true,
                    fileName = res.fileName,
                    notes = notes
                )
                repository.insertSession(session)
                _uiState.value = currentState.copy(isSaved = true)
            }
        }
    }

    fun reset() {
        _uiState.value = FileAnalysisUiState.Idle
    }
}
