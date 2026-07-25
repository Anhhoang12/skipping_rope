package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AudioFileProcessor(private val context: Context) {

    companion object {
        private const val TAG = "AudioFileProcessor"
        private const val WAVEFORM_SAMPLE_COUNT = 150
    }

    suspend fun analyzeAudioFile(
        fileUri: Uri,
        sensitivity: Float = 0.5f,
        debounceIntervalMs: Long = 250L
    ): Result<FileAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(fileUri)
            val pcmDataResult = decodeAudioToPcm(fileUri)
                ?: return@withContext Result.failure(Exception("Unable to decode audio format. Ensure it is a valid WAV, MP3, or M4A file."))

            val (pcmSamples, sampleRate, durationMs) = pcmDataResult
            if (pcmSamples.isEmpty() || durationMs <= 0) {
                return@withContext Result.failure(Exception("Audio file is empty or has zero duration."))
            }

            // Downsample for visual waveform display
            val waveformPoints = generateWaveformPoints(pcmSamples, WAVEFORM_SAMPLE_COUNT)

            // Detect peaks across full PCM audio
            val peakTimestampsMs = detectPeaksInPcm(
                pcmSamples = pcmSamples,
                sampleRate = sampleRate,
                sensitivity = sensitivity,
                debounceIntervalMs = debounceIntervalMs
            )

            val totalJumps = peakTimestampsMs.size
            val durationSeconds = max(1L, durationMs / 1000L)
            val avgJpm = if (durationSeconds > 0) ((totalJumps * 60L) / durationSeconds).toInt() else 0

            // Calculate longest streak
            var maxStreak = 0
            var currentStreak = 0
            var prevPeakMs = 0L

            for (peakMs in peakTimestampsMs) {
                if (prevPeakMs == 0L || (peakMs - prevPeakMs) <= 3000L) {
                    currentStreak++
                } else {
                    currentStreak = 1
                }
                if (currentStreak > maxStreak) {
                    maxStreak = currentStreak
                }
                prevPeakMs = peakMs
            }

            val result = FileAnalysisResult(
                fileName = fileName,
                totalJumps = totalJumps,
                durationSeconds = durationSeconds,
                avgJpm = avgJpm,
                maxStreak = maxStreak,
                peakTimestampsMs = peakTimestampsMs,
                waveformPoints = waveformPoints
            )

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio file", e)
            Result.failure(e)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Audio Recording"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "Audio Recording"
            }
        }
        return name
    }

    private fun decodeAudioToPcm(uri: Uri): Triple<ShortArray, Int, Long>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "MediaExtractor setDataSource failed", e)
            return null
        }

        var trackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = trackFormat
                break
            }
        }

        if (trackIndex == -1 || format == null) {
            extractor.release()
            return null
        }

        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            16000
        }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val durationMs = durationUs / 1000L

        val decoder: MediaCodec
        try {
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Decoder creation failed for mime $mime", e)
            extractor.release()
            return null
        }

        val pcmList = mutableListOf<Short>()
        val info = MediaCodec.BufferInfo()
        var isExtractorEOS = false
        var isDecoderEOS = false

        val timeoutUs = 5000L

        while (!isDecoderEOS) {
            if (!isExtractorEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isExtractorEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outputBufferIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
            if (outputBufferIndex >= 0) {
                val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                    val shortBuffer = outputBuffer.asShortBuffer()
                    while (shortBuffer.hasRemaining()) {
                        pcmList.add(shortBuffer.get())
                    }
                }
                decoder.releaseOutputBuffer(outputBufferIndex, false)

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isDecoderEOS = true
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        return Triple(pcmList.toShortArray(), sampleRate, durationMs)
    }

    private fun generateWaveformPoints(samples: ShortArray, pointCount: Int): List<Float> {
        if (samples.isEmpty()) return List(pointCount) { 0f }
        val chunkSize = max(1, samples.size / pointCount)
        val result = mutableListOf<Float>()

        var globalMax = 1
        for (sample in samples) {
            val absVal = abs(sample.toInt())
            if (absVal > globalMax) globalMax = absVal
        }

        for (i in 0 until pointCount) {
            val start = i * chunkSize
            val end = min(samples.size, start + chunkSize)
            var maxAmp = 0
            for (j in start until end) {
                val absVal = abs(samples[j].toInt())
                if (absVal > maxAmp) maxAmp = absVal
            }
            result.add((maxAmp.toFloat() / globalMax.toFloat()).coerceIn(0.05f, 1.0f))
        }

        return result
    }

    private fun detectPeaksInPcm(
        pcmSamples: ShortArray,
        sampleRate: Int,
        sensitivity: Float,
        debounceIntervalMs: Long
    ): List<Long> {
        val peakMsList = mutableListOf<Long>()
        if (pcmSamples.isEmpty() || sampleRate <= 0) return peakMsList

        val frameSize = max(256, sampleRate / 30) // ~33ms frames
        val minSampleGap = (sampleRate * debounceIntervalMs / 1000L).toInt()

        var noiseFloor = 0.01f
        var lastPeakSampleIndex = -minSampleGap

        var i = 0
        var prevSample = 0.toShort()

        while (i < pcmSamples.size) {
            val frameEnd = min(pcmSamples.size, i + frameSize)
            var sumSquareDiff = 0.0
            var maxPeak = 0

            for (j in i until frameEnd) {
                val current = pcmSamples[j]
                val diff = current - prevSample
                prevSample = current
                sumSquareDiff += (diff * diff).toDouble()
                val absVal = abs(diff)
                if (absVal > maxPeak) maxPeak = absVal
            }

            val frameCount = frameEnd - i
            val rmsDiff = kotlin.math.sqrt(sumSquareDiff / frameCount).toFloat() / 32768f
            val peakAmp = maxPeak.toFloat() / 32768f

            if (rmsDiff < noiseFloor * 2.5f) {
                noiseFloor = 0.95f * noiseFloor + 0.05f * rmsDiff
            }

            val thresholdMultiplier = 7.5f - (sensitivity * 5.8f)
            val threshold = max(0.025f, noiseFloor * thresholdMultiplier + (1.0f - sensitivity) * 0.035f)

            if (peakAmp > threshold && rmsDiff > threshold * 0.5f) {
                if ((i - lastPeakSampleIndex) >= minSampleGap) {
                    val timestampMs = (i.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
                    peakMsList.add(timestampMs)
                    lastPeakSampleIndex = i
                }
            }

            i += frameSize
        }

        return peakMsList
    }
}
