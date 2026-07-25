package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val sensitivity: Float = 0.5f,
    val debounceMs: Long = 250L,
    val soundFeedback: Boolean = true,
    val hapticFeedback: Boolean = true,
    val darkTheme: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("skipping_rope_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            sensitivity = prefs.getFloat("sensitivity", 0.5f),
            debounceMs = prefs.getLong("debounceMs", 250L),
            soundFeedback = prefs.getBoolean("soundFeedback", true),
            hapticFeedback = prefs.getBoolean("hapticFeedback", true),
            darkTheme = prefs.getBoolean("darkTheme", true)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateSensitivity(value: Float) {
        prefs.edit().putFloat("sensitivity", value).apply()
        _uiState.value = _uiState.value.copy(sensitivity = value)
    }

    fun updateDebounce(valueMs: Long) {
        prefs.edit().putLong("debounceMs", valueMs).apply()
        _uiState.value = _uiState.value.copy(debounceMs = valueMs)
    }

    fun updateSoundFeedback(enabled: Boolean) {
        prefs.edit().putBoolean("soundFeedback", enabled).apply()
        _uiState.value = _uiState.value.copy(soundFeedback = enabled)
    }

    fun updateHapticFeedback(enabled: Boolean) {
        prefs.edit().putBoolean("hapticFeedback", enabled).apply()
        _uiState.value = _uiState.value.copy(hapticFeedback = enabled)
    }

    fun updateDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("darkTheme", enabled).apply()
        _uiState.value = _uiState.value.copy(darkTheme = enabled)
    }
}
