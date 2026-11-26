package fr.nosflix.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.pow

private fun segment(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)
private fun mix(a: Float, b: Float, f: Float): Float = a + (b - a) * f

/**
 * Intro d'environ 3,2 s : le logo NF apparaît, impact rouge vers 1 s, reflet sur les lettres,
 * puis le mot NOSFLIX. Une touche ou un appui la passe.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    val finish = {
        if (!finished) {
            finished = true
            done()
        }
    }
    val clock = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        IntroSound.play()
        clock.animateTo(3200f, tween(3200, easing = LinearEasing))
        finish()
    }

    val t = clock.value
    val appear = easeOut(segment(t, 0f, 990f))
    val logoScale = when {
        t < 990f -> mix(0.55f, 0.97f, appear)
        t < 1090f -> mix(0.97f, 1.07f, segment(t, 990f, 1090f))
        else -> mix(1.07f, 1f, easeOut(segment(t, 1090f, 1600f)))
    }
    val blur = (1f - appear) * 16f
    val glow = when {
        t < 900f -> 0f
        t < 1080f -> segment(t, 900f, 1080f)
        else -> mix(1f, 0.35f, segment(t, 1080f, 3100f))
    }
    val wave = segment(t, 980f, 1880f)
    val sheen = if (t in 1300f..2400f) segment(t, 1300f, 2400f) else null
    val word = easeOut(segment(t, 1800f, 2700f))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    finish()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) { detectTapGestures { finish() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            if (glow > 0f) {
                val radius = size.minDimension * 0.62f * (0.6f + 0.4f * glow)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to NfColors.Accent.copy(alpha = 0.5f * glow),
                        0.38f to NfColors.Accent.copy(alpha = 0.12f * glow),
                        1f to Color.Transparent,
                        center = c,
                        radius = radius,
                    ),
                    radius = radius,
                    center = c,
                )
            }
            if (wave > 0f && wave < 1f) {
                val w = size.width * wave
                val y = c.y - 28.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0xFFFF5A4F).copy(alpha = 1f - wave),
                        1f to Color.Transparent,
                        startX = c.x - w / 2f,
                        endX = c.x + w / 2f,
                    ),
                    topLeft = Offset(c.x - w / 2f, y),
                    size = Size(w, 2.dp.toPx()),
                )
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            NfLetters(
                modifier = Modifier
                    .size(width = 200.dp, height = 167.dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                        alpha = appear
                    }
                    .blur(blur.dp),
                sheen = sheen,
            )
            // Version publique : le logo est déjà le mot « nosflix », pas besoin de le répéter dessous.
            if (fr.nosflix.app.BuildConfig.MONOGRAM) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "NOSFLIX",
                    color = Color.White.copy(alpha = word),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = mix(0.9f, 0.32f, word).em,
                )
            }
        }
    }
}
