package com.unshoo.pixelmusic.spotify

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.sin

/** Draw-only motion, capped at 30fps; no list recomposition or off-screen animation. */
@Composable
fun LivingHomeBackground() {
    val phase = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val base = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = .07f)
    val secondary = MaterialTheme.colorScheme.tertiary.copy(alpha = .05f)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (ValueAnimator.areAnimatorsEnabled()) phase.floatValue = (phase.floatValue + .003f) % 6.283185f
                delay(33)
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        val t = phase.floatValue
        val center = Offset(size.width * (.3f + .2f * sin(t)), size.height * (.25f + .12f * sin(t * 2)))
        drawRect(Brush.radialGradient(listOf(primary, Color.Transparent), center, size.width * .95f))
        drawRect(Brush.radialGradient(listOf(secondary, Color.Transparent),
            Offset(size.width - center.x, size.height * .72f), size.width * .9f))
    }
}

