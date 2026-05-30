package com.cin.bilateralsleep.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time bilateral sleep synth DSP. Algorithmically identical to the Python
 * reference (desktop/bilateral_sleep.py):
 *
 *   carrier  = Poisson clicks -> RBJ resonant bandpass  +  brown-noise bed
 *   envelope = equal-power (cosine-law) pan LFO, swinging the carrier L<->R
 *   optional faint binaural sines under the carrier
 *
 * The JVM handles per-sample recursion comfortably at 48 kHz, so the bandpass
 * is a direct biquad recursion (mathematically the same as the desktop's
 * impulse-response convolution). One engine is owned by one audio thread; all
 * mutable state lives here and carries across blocks, so buffer seams are clean.
 */
class BilateralEngine(
    private val params: SynthParams,
    private val sampleRate: Int = 48000,
) {
    private val rng = Random()

    // --- resonant bandpass (recomputed only when WARMTH / Q change) ---
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0
    private var clickGain = 1.0          // CLICK_PEAK / kernelPeak
    private var lastCenter = Double.NaN
    private var lastQ = Double.NaN

    // --- click scheduler (Poisson) ---
    private var samplesToNextClick = 0.0

    // --- brown bed (leaky integrator) ---
    private val brownA = exp(-2.0 * PI * BROWN_POLE_HZ / sampleRate)
    private val brownNorm = sqrt((1.0 + brownA) / (1.0 - brownA)) * 0.3
    private var brownState = 0.0

    // --- phases ---
    private var panPhase = 0.0
    private var binPhaseL = 0.0
    private var binPhaseR = 0.0

    // --- per-sample smoothed controls (click-free) ---
    private var curVolume = 0.0
    private var curEnvelope = 0.0

    init {
        updateBandpass(params.carrierCenter, params.carrierQ)
        samplesToNextClick = nextInterval(max(params.crackleDensity, MIN_DENSITY))
    }

    private fun nextInterval(density: Double): Double =
        -ln(1.0 - rng.nextDouble()) * (sampleRate / density)

    private fun updateBandpass(center: Double, q: Double) {
        val w0 = 2.0 * PI * center / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / (2.0 * max(q, 0.1))
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = (-2.0 * cosw) / a0
        a2 = (1.0 - alpha) / a0
        // normalise click loudness against the bandpass impulse-response peak
        var p1 = 0.0
        var p2 = 0.0
        var peak = 0.0
        for (n in 0 until KERNEL_LEN) {
            val x = if (n == 0) 1.0 else 0.0
            val y = b0 * x + p1
            p1 = b1 * x - a1 * y + p2
            p2 = b2 * x - a2 * y
            val ay = abs(y)
            if (ay > peak) peak = ay
        }
        clickGain = if (peak > 0.0) CLICK_PEAK / peak else 1.0
        lastCenter = center
        lastQ = q
    }

    /**
     * Render [frames] interleaved stereo frames into [out] (length >= frames*2).
     * Returns false once a fade-to-silence has fully completed (caller stops).
     */
    fun render(out: FloatArray, frames: Int): Boolean {
        val center = params.carrierCenter
        val q = params.carrierQ
        if (center != lastCenter || q != lastQ) updateBandpass(center, q)

        val density = max(params.crackleDensity, MIN_DENSITY)
        val brownLevel = params.brownLevel
        val hard = params.panShapeHard
        val altInc = 2.0 * PI * params.altRate / sampleRate
        val binauralHz = params.binauralHz
        val binActive = binauralHz > 0.0
        val binIncL = 2.0 * PI * BINAURAL_BASE_HZ / sampleRate
        val binIncR = 2.0 * PI * (BINAURAL_BASE_HZ + binauralHz) / sampleRate

        val targetEnv = params.targetEnvelope
        val envInc = 1.0 / (max(params.fadeSeconds, 0.01) * sampleRate)
        val targetVol = params.volume
        val volInc = 1.0 / (VOLUME_SMOOTH_SEC * sampleRate)
        val tanh3 = tanh(3.0)

        var i = 0
        for (n in 0 until frames) {
            // smooth envelope + volume toward their targets
            curEnvelope = when {
                curEnvelope < targetEnv -> min(targetEnv, curEnvelope + envInc)
                curEnvelope > targetEnv -> max(targetEnv, curEnvelope - envInc)
                else -> curEnvelope
            }
            curVolume = when {
                curVolume < targetVol -> min(targetVol, curVolume + volInc)
                curVolume > targetVol -> max(targetVol, curVolume - volInc)
                else -> curVolume
            }

            // crackle: Poisson impulses into the resonant bandpass
            samplesToNextClick -= 1.0
            var x = 0.0
            while (samplesToNextClick <= 0.0) {
                val amp = (0.6 + 0.4 * rng.nextDouble()) * if (rng.nextBoolean()) 1.0 else -1.0
                x += amp * clickGain
                samplesToNextClick += nextInterval(density)
            }
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y

            // brown bed
            brownState = brownA * brownState + (1.0 - brownA) * rng.nextGaussian()
            val sourceMono = y + brownState * brownNorm * brownLevel

            // equal-power pan
            panPhase += altInc
            val pan = sin(panPhase)
            val pp = if (hard) tanh(3.0 * pan) / tanh3 else pan
            val theta = (pp + 1.0) * (PI / 4.0)
            var l = sourceMono * cos(theta)
            var r = sourceMono * sin(theta)

            // binaural under-layer
            if (binActive) {
                binPhaseL += binIncL
                binPhaseR += binIncR
                l += BINAURAL_AMP * sin(binPhaseL)
                r += BINAURAL_AMP * sin(binPhaseR)
            }

            val g = curVolume * curEnvelope
            out[i++] = (l * g).coerceIn(-1.0, 1.0).toFloat()
            out[i++] = (r * g).coerceIn(-1.0, 1.0).toFloat()
        }

        // keep phase accumulators small
        val twoPi = 2.0 * PI
        panPhase %= twoPi
        binPhaseL %= twoPi
        binPhaseR %= twoPi

        return !(targetEnv == 0.0 && curEnvelope <= 0.0)
    }

    companion object {
        const val CLICK_PEAK = 0.7
        const val BROWN_POLE_HZ = 120.0
        const val BINAURAL_BASE_HZ = 200.0
        const val BINAURAL_AMP = 0.06
        const val KERNEL_LEN = 512
        const val MIN_DENSITY = 0.01
        const val VOLUME_SMOOTH_SEC = 0.05
    }
}
