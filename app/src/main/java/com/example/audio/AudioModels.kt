package com.example.audio

data class LiveAudioState(
    val currentVolumeNormalized: Float = 0f,
    val isLowAudioQuality: Boolean = false,
    val noiseFloor: Float = 0.02f,
    val currentThreshold: Float = 0.15f
)

data class PeakInfo(
    val timestampMs: Long,
    val amplitude: Float
)

data class FileAnalysisResult(
    val fileName: String,
    val totalJumps: Int,
    val durationSeconds: Long,
    val avgJpm: Int,
    val maxStreak: Int,
    val peakTimestampsMs: List<Long>,
    val waveformPoints: List<Float>
)
