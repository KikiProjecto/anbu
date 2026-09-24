package com.anbu.research.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anbu.research.ui.theme.*

enum class ButtonVariant { PRIMARY, SECONDARY, DANGER, GHOST }

@Composable
fun GlowingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    icon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "buttonScale"
    )

    val shape = RoundedCornerShape(14.dp)

    val backgroundBrush = when (variant) {
        ButtonVariant.PRIMARY -> if (enabled) {
            Brush.horizontalGradient(listOf(AnbuCyan, AnbuViolet))
        } else {
            Brush.horizontalGradient(listOf(AnbuSurfaceElevated, AnbuSurfaceElevated))
        }
        ButtonVariant.SECONDARY -> Brush.horizontalGradient(listOf(AnbuSurfaceElevated, AnbuSurfaceElevated))
        ButtonVariant.DANGER -> Brush.horizontalGradient(listOf(AnbuCrimson, Color(0xFF991B1B)))
        ButtonVariant.GHOST -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    val contentColor = when (variant) {
        ButtonVariant.PRIMARY -> if (enabled) AnbuBgDark else AnbuTextMuted
        ButtonVariant.SECONDARY -> if (enabled) AnbuCyan else AnbuTextMuted
        ButtonVariant.DANGER -> AnbuTextPrimary
        ButtonVariant.GHOST -> if (enabled) AnbuCyan else AnbuTextMuted
    }

    val borderModifier = when (variant) {
        ButtonVariant.SECONDARY -> Modifier.border(1.dp, if (enabled) AnbuCyan.copy(alpha = 0.5f) else AnbuBorder, shape)
        ButtonVariant.PRIMARY -> if (enabled) Modifier.border(1.dp, AnbuCyanGlow.copy(alpha = 0.6f), shape) else Modifier
        else -> Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(backgroundBrush)
            .then(borderModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.wrapContentWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            )
        }
    }
}
