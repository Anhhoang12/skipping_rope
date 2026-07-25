package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WaveformCanvas(
    waveformPoints: List<Float>,
    peakTimestampsMs: List<Long>,
    totalDurationSeconds: Long,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    peakColor: Color = MaterialTheme.colorScheme.secondary
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        if (waveformPoints.isEmpty()) return@Canvas

        val canvasWidth = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f

        val barCount = waveformPoints.size
        val barGap = 3f
        val totalGaps = barGap * (barCount - 1)
        val barWidth = (canvasWidth - totalGaps) / barCount

        // Draw waveform amplitude bars
        for (i in waveformPoints.indices) {
            val amplitude = waveformPoints[i].coerceIn(0.05f, 1.0f)
            val barHeight = amplitude * (canvasHeight * 0.85f)
            val x = i * (barWidth + barGap)
            val y = centerY - (barHeight / 2f)

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }

        // Draw detected peak markers
        if (totalDurationSeconds > 0) {
            for (peakMs in peakTimestampsMs) {
                val progress = (peakMs / 1000f) / totalDurationSeconds.toFloat()
                if (progress in 0f..1f) {
                    val x = progress * canvasWidth
                    drawLine(
                        color = peakColor,
                        start = Offset(x, 0f),
                        end = Offset(x, canvasHeight),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = peakColor,
                        radius = 4f,
                        center = Offset(x, 8f)
                    )
                }
            }
        }
    }
}
