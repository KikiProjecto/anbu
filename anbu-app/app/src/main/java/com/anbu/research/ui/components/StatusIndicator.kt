package com.anbu.research.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anbu.research.ui.AIEngineViewModel.ChatStatus
import com.anbu.research.ui.theme.*

@Composable
fun StatusIndicator(
    status: ChatStatus,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (badgeColor, statusLabel) = when (status) {
        ChatStatus.IDLE -> AnbuTextMuted to "Engine Standby"
        ChatStatus.SEARCHING -> AnbuCyan to "Searching Local SQLite & USearch Vectors..."
        ChatStatus.GENERATING -> AnbuViolet to "Synthesizing Response with MoE Experts..."
        ChatStatus.DONE -> AnbuEmerald to "Synthesis Complete"
        ChatStatus.STOPPED -> AnbuAmber to "Generation Stopped — Partial Answer"
        ChatStatus.ERROR -> AnbuCrimson to "Inference Failed"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AnbuSurfaceDark)
            .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Status dot indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (status == ChatStatus.SEARCHING || status == ChatStatus.GENERATING) {
                                badgeColor.copy(alpha = pulseAlpha)
                            } else {
                                badgeColor
                            }
                        )
                )

                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = badgeColor
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(visible = status == ChatStatus.SEARCHING || status == ChatStatus.GENERATING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (status == ChatStatus.SEARCHING) AnbuCyan else AnbuViolet,
                    trackColor = AnbuSurfaceElevated
                )
            }
        }
    }
}
