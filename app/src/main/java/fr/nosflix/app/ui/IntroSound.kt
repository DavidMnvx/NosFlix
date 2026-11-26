package fr.nosflix.app.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Son d'intro NosFlix, synthétisé à la volée (création originale) :
 * un souffle qui monte, puis un impact grave dans les basses et une nappe sourde.
 */
object IntroSound {

    private const val SAMPLE_RATE = 44_100
    private const val DURATION_S = 4.0

    fun play() {
        thread(name = "nosflix-intro-sound", isDaemon = true) {
            runCatching {
                val pcm = synthesize()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / SAMPLE_RATE + 300)
                track.release()
            }
        }
    }

    /** Rampe exponentielle de v1 (à t1) vers v2 (à t2). */
    private fun ramp(t: Double, t1: Double, v1: Double, t2: Double, v2: Double): Double = when {
        t <= t1 -> v1
        t >= t2 -> v2
        else -> v1 * (v2 / v1).pow((t - t1) / (t2 - t1))
    }

    private fun synthesize(): ShortArray {
        val sr = SAMPLE_RATE.toDouble()
        val n = (SAMPLE_RATE * DURATION_S).toInt()
        val out = ShortArray(n)
        val random = java.util.Random(7)
        val twoPi = 2 * PI
        val t0 = 0.05
        val hit = t0 + 0.95

        var noiseLp = 0.0
        var subPhase = 0.0
        var punchPhase = 0.0
        var low1 = 0.0
        var low2 = 0.0
        val padFreq = doubleArrayOf(55.0, 82.4)
        val padPhase = DoubleArray(2)
        val padLp = DoubleArray(2)
        val lowAlpha = 1 - exp(-twoPi * 320.0 / sr)

        for (i in 0 until n) {
            val t = i / sr
            var sample = 0.0

            // 1. Souffle filtré qui monte jusqu'à l'impact.
            if (t >= t0 && t < t0 + 1.1) {
                val cutoff = ramp(t, t0, 200.0, t0 + 0.9, 2400.0)
                val a = 1 - exp(-twoPi * cutoff / sr)
                noiseLp += a * ((random.nextDouble() * 2 - 1) - noiseLp)
                val gain = if (t < t0 + 0.85) ramp(t, t0, 0.0001, t0 + 0.85, 0.3) else ramp(t, t0 + 0.85, 0.3, t0 + 1.0, 0.0001)
                sample += noiseLp * gain
            }

            // 2. Impact grave : sinus qui descend de 72 à 31 Hz, saturé, plus une attaque triangle.
            var low = 0.0
            if (t >= hit && t < hit + 3.4) {
                val f = ramp(t, hit, 72.0, hit + 1.6, 31.0)
                subPhase += twoPi * f / sr
                val gain = if (t < hit + 0.02) ramp(t, hit, 0.0001, hit + 0.02, 1.0) else ramp(t, hit + 0.02, 1.0, hit + 3.2, 0.0001)
                low += tanh(2.2 * sin(subPhase)) * gain
            }
            if (t >= hit && t < hit + 0.9) {
                val f = ramp(t, hit, 150.0, hit + 0.5, 46.0)
                punchPhase += f / sr
                val triangle = 4 * abs(punchPhase - floor(punchPhase + 0.5)) - 1
                val gain = if (t < hit + 0.01) ramp(t, hit, 0.0001, hit + 0.01, 0.4) else ramp(t, hit + 0.01, 0.4, hit + 0.8, 0.0001)
                low += triangle * gain
            }
            low1 += lowAlpha * (low - low1)
            low2 += lowAlpha * (low1 - low2)
            sample += low2

            // 3. Nappe sourde qui reste après l'impact.
            if (t >= hit + 0.1 && t < hit + 3.8) {
                val cutoff = 120.0 + 300.0 * ((t - hit) / 1.5).coerceIn(0.0, 1.0)
                val a = 1 - exp(-twoPi * cutoff / sr)
                val gain = if (t < hit + 0.6) ramp(t, hit + 0.1, 0.0001, hit + 0.6, 0.1) else ramp(t, hit + 0.6, 0.1, hit + 3.6, 0.0001)
                for (k in 0..1) {
                    padPhase[k] += padFreq[k] / sr
                    val saw = 2 * (padPhase[k] - floor(padPhase[k] + 0.5))
                    padLp[k] += a * (saw - padLp[k])
                    sample += padLp[k] * gain
                }
            }

            val v = (sample * 0.9).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
