package com.soniq.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.random.Random

data class RippleWave(
    val id: Long,
    val center: Offset,
    val anim: Animatable<Float, *>
)

data class SplashDroplet(
    val offset: Offset,
    val velocity: Offset,
    val size: Float,
    val alpha: Float
)

/**
 * Bubbly Ripple Touch Physics (Water Droplet Splashes)
 * Wraps content and intercepts tap/drag events to emit fluid waves and water droplet particles.
 */
@Composable
fun FluidTouchCanvas(
    rippleColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val ripples = remember { mutableStateListOf<RippleWave>() }
    val scope = rememberCoroutineScope()

    fun triggerSplash(center: Offset) {
        val waveAnim = Animatable(0f)
        val ripple = RippleWave(System.nanoTime(), center, waveAnim)
        ripples.add(ripple)

        scope.launch {
            waveAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 850, easing = LinearEasing)
            )
            ripples.remove(ripple)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    triggerSplash(offset)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change: PointerInputChange, _ ->
                    if (Random.nextFloat() > 0.65f) {
                        triggerSplash(change.position)
                    }
                }
            }
    ) {
        content()

        Canvas(modifier = Modifier.fillMaxSize()) {
            ripples.forEach { ripple ->
                val progress = ripple.anim.value
                val maxRadius = 140f
                val radius = progress * maxRadius
                val alpha = (1f - progress).coerceIn(0f, 1f)

                // Outer Meniscus Ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            rippleColor.copy(alpha = alpha * 0.45f),
                            Color.Transparent
                        ),
                        center = ripple.center,
                        radius = radius + 20f
                    ),
                    radius = radius,
                    center = ripple.center,
                    style = Stroke(width = (6f * (1f - progress)).coerceAtLeast(1f))
                )

                // Core Water Bubble Ring
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.7f),
                    radius = (radius * 0.6f),
                    center = ripple.center,
                    style = Stroke(width = (2.5f * (1f - progress)).coerceAtLeast(0.5f))
                )

                // Sub-droplet splashes
                for (i in 0..4) {
                    val angle = (i * 72.0 + (progress * 45.0)).toDouble()
                    val dist = radius * 1.15f
                    val dx = (dist * kotlin.math.cos(Math.toRadians(angle))).toFloat()
                    val dy = (dist * kotlin.math.sin(Math.toRadians(angle))).toFloat()
                    drawCircle(
                        color = rippleColor.copy(alpha = alpha * 0.8f),
                        radius = (3.5f * (1f - progress)).coerceAtLeast(1f),
                        center = Offset(ripple.center.x + dx, ripple.center.y + dy)
                    )
                }
            }
        }
    }
}
