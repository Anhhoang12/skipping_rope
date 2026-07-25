package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class RealtimeAudioEngine {

    companion object {
        private const val TAG = "RealtimeAudioEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE = 512
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _audioState = MutableStateFlow(LiveAudioState())
    val audioState: StateFlow<LiveAudioState> = _audioState.asStateFlow()

    // Configuration parameters
    var sensitivity: Float = 0.5f // 0.0f (least sensitive) to 1.0f (most sensitive)
    var debounceIntervalMs: Long = 250L // minimum time between jumps (250ms = 240 max JPM)

    // Dynamic state
    private var lastJumpTimestampMs: Long = 0L
    private var noiseFloorRMS: Float = 0.005f
    private var prevSample: Short = 0

    // Calibration state
    private var isCalibrating: Boolean = false
    private val calibrationPeaks = mutableListOf<Float>()
    private var onCalibrationStep: ((currentCount: Int, targetCount: Int) -> Unit)? = null
    private var onCalibrationComplete: ((recommendedSensitivity: Float) -> Unit)? = null

    // Event listeners
    private var onJumpDetected: ((timestampMs: Long) -> Unit)? = null
    private var onErrorOccurred: ((errorMessage: String) -> Unit)? = null

    fun setJumpListener(listener: (timestampMs: Long) -> Unit) {
        this.onJumpDetected = listener
    }

    fun setErrorListener(listener: (errorMessage: String) -> Unit) {
        this.onErrorOccurred = listener
    }

    fun startCalibration(
        targetCount: Int = 10,
        onStep: (currentCount: Int, targetCount: Int) -> Unit,
        onComplete: (recommendedSensitivity: Float) -> Unit
    ) {
        isCalibrating = true
        calibrationPeaks.clear()
        onCalibrationStep = onStep
        onCalibrationComplete = onComplete
        onStep(0, targetCount)
    }

    fun cancelCalibration() {
        isCalibrating = false
        calibrationPeaks.clear()
        onCalibrationStep = null
        onCalibrationComplete = null
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (recordingJob?.isActive == true) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onErrorOccurred?.invoke("Audio hardware not supported for audio recording.")
            return false
        }

        val bufferSize = max(minBufferSize, FRAME_SIZE * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onErrorOccurred?.invoke("Failed to initialize microphone.")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            onErrorOccurred?.invoke("Microphone access error: ${e.localizedMessage}")
            return false
        }

        recordingJob = scope.launch {
            val audioBuffer = ShortArray(FRAME_SIZE)
            var consecutiveLowFrames = 0

            while (isActive) {
                val record = audioRecord ?: break
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) break

                val readSize = record.read(audioBuffer, 0, FRAME_SIZE)
                if (readSize > 0) {
                    processAudioFrame(audioBuffer, readSize)
                } else if (readSize < 0) {
                    consecutiveLowFrames++
                    if (consecutiveLowFrames > 10) {
                        _audioState.value = _audioState.value.copy(isLowAudioQuality = true)
                    }
                }
            }
        }

        return true
    }

    private fun processAudioFrame(buffer: ShortArray, length: Int) {
        var sumSquares = 0.0
        var maxDiff = 0

        // Calculate High-Pass / Transient energy
        for (i in 0 until length) {
            val current = buffer[i]
            val diff = current - prevSample
            prevSample = current
            sumSquares += (diff * diff).toDouble()
            val absDiff = abs(diff.toInt())
            if (absDiff > maxDiff) {
                maxDiff = absDiff
            }
        }

        val rmsDiff = sqrt(sumSquares / length).toFloat() / 32768f
        val peakAmp = maxDiff.toFloat() / 32768f

        // Update noise floor dynamically using exponential moving average
        if (rmsDiff < noiseFloorRMS * 2.5f) {
            noiseFloorRMS = 0.97f * noiseFloorRMS + 0.03f * rmsDiff
        }
        noiseFloorRMS = max(0.002f, min(0.1f, noiseFloorRMS))

        // Dynamic threshold based on sensitivity
        // Sensitivity 1.0 -> multiplier ~ 2.0 (very sensitive)
        // Sensitivity 0.0 -> multiplier ~ 8.0 (strict)
        val sensitivityMultiplier = 8.5f - (sensitivity * 6.5f)
        val dynamicThreshold = max(0.025f, noiseFloorRMS * sensitivityMultiplier + (1.0f - sensitivity) * 0.04f)

        // Normalize current volume for UI audio level indicator
        val normalizedVolume = min(1.0f, peakAmp * 3.5f)

        _audioState.value = LiveAudioState(
            currentVolumeNormalized = normalizedVolume,
            isLowAudioQuality = rmsDiff < 0.0005f,
            noiseFloor = noiseFloorRMS,
            currentThreshold = dynamicThreshold
        )

        // Peak detection with threshold & debounce
        val now = System.currentTimeMillis()
        val isTransientPeak = peakAmp > dynamicThreshold && rmsDiff > dynamicThreshold * 0.6f

        if (isTransientPeak && (now - lastJumpTimestampMs >= debounceIntervalMs)) {
            lastJumpTimestampMs = now

            if (isCalibrating) {
                calibrationPeaks.add(peakAmp)
                val currentCount = calibrationPeaks.size
                val targetCount = 10
                onCalibrationStep?.invoke(currentCount, targetCount)

                if (currentCount >= targetCount) {
                    finishCalibration()
                }
            } else {
                onJumpDetected?.invoke(now)
            }
        }
    }

    private fun finishCalibration() {
        if (calibrationPeaks.isEmpty()) return
        val avgPeak = calibrationPeaks.average().toFloat()
        // Compute sensitivity value that places dynamicThreshold smoothly below avgPeak
        val recommendedSens = max(0.2f, min(0.95f, (avgPeak * 2.2f).coerceIn(0.2f, 0.95f)))
        sensitivity = recommendedSens
        val completeCb = onCalibrationComplete
        isCalibrating = false
        calibrationPeaks.clear()
        completeCb?.invoke(recommendedSens)
    }

    fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }
}
