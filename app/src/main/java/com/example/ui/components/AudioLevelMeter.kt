package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.LiveAudioState

@Composable
fun AudioLevelMeter(
    liveAudioState: LiveAudioState,
    modifier: Modifier = Modifier
) {
    val animatedVolume by animateFloatAsState(
        targetValue = liveAudioState.currentVolumeNormalized,
        label = "VolumeAnim"
    )

    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thresholdColor = MaterialTheme.colorScheme.secondary

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUDIO SIGNAL",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            if (liveAudioState.isLowAudioQuality) {
                Text(
                    text = "Low mic signal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        ) {
            val width = size.width
            val height = size.height

            // Background Track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = Size(width, height),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )

            // Volume level fill
            val volumeWidth = (animatedVolume * width).coerceIn(0f, width)
            if (volumeWidth > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset.Zero,
                    size = Size(volumeWidth, height),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
            }

            // Threshold marker
            val thresholdX = (liveAudioState.currentThreshold * width * 3f).coerceIn(0f, width - 4f)
            drawLine(
                color = thresholdColor,
                start = Offset(thresholdX, 0f),
                end = Offset(thresholdX, height),
                strokeWidth = 4.dp.toPx()
            )
        }
    }
}
