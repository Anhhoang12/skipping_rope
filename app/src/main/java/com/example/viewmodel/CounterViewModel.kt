package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioFeedbackPlayer
import com.example.audio.LiveAudioState
import com.example.audio.RealtimeAudioEngine
import com.example.data.database.AppDatabase
import com.example.data.entity.JumpSessionEntity
import com.example.data.repository.JumpSessionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

enum class SessionStatus {
    STOPPED, RUNNING, PAUSED
}

data class CounterUiState(
    val status: SessionStatus = SessionStatus.STOPPED,
    val jumps: Int = 0,
    val durationSeconds: Long = 0L,
    val currentJpm: Int = 0,
    val avgJpm: Int = 0,
    val currentStreak: Int = 0,
    val maxStreak: Int = 0,
    val sensitivity: Float = 0.5f,
    val debounceMs: Long = 250L,
    val keepScreenOn: Boolean = false,
    val liveAudioState: LiveAudioState = LiveAudioState(),
    val isCalibrating: Boolean = false,
    val calibrationCount: Int = 0,
    val calibrationTarget: Int = 10,
    val errorMessage: String? = null,
    val savedSessionSuccess: Boolean = false
)

class CounterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: JumpSessionRepository
    private val audioEngine = RealtimeAudioEngine()
    private val audioFeedbackPlayer = AudioFeedbackPlayer(application)

    private val _uiState = MutableStateFlow(CounterUiState())
    val uiState: StateFlow<CounterUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var lastJumpTimeMs: Long = 0L

    init {
        val dao = AppDatabase.getDatabase(application).jumpSessionDao()
        repository = JumpSessionRepository(dao)

        audioEngine.setJumpListener { timestampMs ->
            onJumpDetected(timestampMs)
        }

        audioEngine.setErrorListener { errorMsg ->
            _uiState.value = _uiState.value.copy(errorMessage = errorMsg)
        }

        viewModelScope.launch {
            audioEngine.audioState.collect { audioState ->
                _uiState.value = _uiState.value.copy(liveAudioState = audioState)
            }
        }
    }

    fun setPreferences(soundEnabled: Boolean, hapticEnabled: Boolean, debounceMs: Long, sensitivity: Float) {
        audioFeedbackPlayer.setFeedbackPreferences(soundEnabled, hapticEnabled)
        audioEngine.debounceIntervalMs = debounceMs
        audioEngine.sensitivity = sensitivity
        _uiState.value = _uiState.value.copy(debounceMs = debounceMs, sensitivity = sensitivity)
    }

    fun updateSensitivity(value: Float) {
        audioEngine.sensitivity = value
        _uiState.value = _uiState.value.copy(sensitivity = value)
    }

    fun updateDebounce(valueMs: Long) {
        audioEngine.debounceIntervalMs = valueMs
        _uiState.value = _uiState.value.copy(debounceMs = valueMs)
    }

    fun startSession(): Boolean {
        val success = audioEngine.startListening()
        if (success) {
            _uiState.value = _uiState.value.copy(
                status = SessionStatus.RUNNING,
                keepScreenOn = true,
                errorMessage = null,
                savedSessionSuccess = false
            )
            startTimer()
        }
        return success
    }

    fun pauseSession() {
        audioEngine.stopListening()
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(
            status = SessionStatus.PAUSED,
            keepScreenOn = false
        )
    }

    fun resumeSession(): Boolean {
        val success = audioEngine.startListening()
        if (success) {
            _uiState.value = _uiState.value.copy(
                status = SessionStatus.RUNNING,
                keepScreenOn = true
            )
            startTimer()
        }
        return success
    }

    fun resetSession() {
        audioEngine.stopListening()
        timerJob?.cancel()
        lastJumpTimeMs = 0L
        _uiState.value = CounterUiState(
            sensitivity = _uiState.value.sensitivity,
            debounceMs = _uiState.value.debounceMs
        )
    }

    fun incrementJump() {
        audioFeedbackPlayer.triggerJumpFeedback()
        val newJumps = _uiState.value.jumps + 1
        val newStreak = _uiState.value.currentStreak + 1
        val newMaxStreak = max(_uiState.value.maxStreak, newStreak)
        updateCalculatedMetrics(newJumps, newStreak, newMaxStreak)
    }

    fun decrementJump() {
        val newJumps = max(0, _uiState.value.jumps - 1)
        val newStreak = max(0, _uiState.value.currentStreak - 1)
        updateCalculatedMetrics(newJumps, newStreak, _uiState.value.maxStreak)
    }

    private fun onJumpDetected(timestampMs: Long) {
        audioFeedbackPlayer.triggerJumpFeedback()
        val currentState = _uiState.value

        var newStreak = currentState.currentStreak + 1
        if (lastJumpTimeMs > 0 && (timestampMs - lastJumpTimeMs) > 3000L) {
            newStreak = 1
        }
        lastJumpTimeMs = timestampMs

        val newJumps = currentState.jumps + 1
        val newMaxStreak = max(currentState.maxStreak, newStreak)

        updateCalculatedMetrics(newJumps, newStreak, newMaxStreak)
    }

    private fun updateCalculatedMetrics(newJumps: Int, newStreak: Int, newMaxStreak: Int) {
        val durationSec = _uiState.value.durationSeconds
        val avgJpm = if (durationSec > 0) ((newJumps * 60L) / durationSec).toInt() else 0

        _uiState.value = _uiState.value.copy(
            jumps = newJumps,
            currentStreak = newStreak,
            maxStreak = newMaxStreak,
            avgJpm = avgJpm
        )
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_uiState.value.status == SessionStatus.RUNNING) {
                delay(1000L)
                val newDuration = _uiState.value.durationSeconds + 1
                val jumps = _uiState.value.jumps
                val avgJpm = if (newDuration > 0) ((jumps * 60L) / newDuration).toInt() else 0

                _uiState.value = _uiState.value.copy(
                    durationSeconds = newDuration,
                    avgJpm = avgJpm
                )
            }
        }
    }

    fun startCalibration() {
        audioEngine.startCalibration(
            targetCount = 10,
            onStep = { count, target ->
                _uiState.value = _uiState.value.copy(
                    isCalibrating = true,
                    calibrationCount = count,
                    calibrationTarget = target
                )
            },
            onComplete = { newSensitivity ->
                _uiState.value = _uiState.value.copy(
                    isCalibrating = false,
                    sensitivity = newSensitivity
                )
            }
        )
        if (_uiState.value.status != SessionStatus.RUNNING) {
            audioEngine.startListening()
        }
    }

    fun cancelCalibration() {
        audioEngine.cancelCalibration()
        _uiState.value = _uiState.value.copy(isCalibrating = false)
    }

    fun saveSession(notes: String? = null) {
        val state = _uiState.value
        if (state.jumps == 0 && state.durationSeconds < 2) return

        viewModelScope.launch {
            val session = JumpSessionEntity(
                totalJumps = state.jumps,
                durationSeconds = state.durationSeconds,
                avgJpm = state.avgJpm,
                maxStreak = state.maxStreak,
                isFromFile = false,
                notes = notes,
                sensitivityUsed = state.sensitivity
            )
            repository.insertSession(session)
            audioEngine.stopListening()
            timerJob?.cancel()
            _uiState.value = _uiState.value.copy(
                status = SessionStatus.STOPPED,
                keepScreenOn = false,
                savedSessionSuccess = true
            )
        }
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stopListening()
        audioFeedbackPlayer.release()
    }
}
