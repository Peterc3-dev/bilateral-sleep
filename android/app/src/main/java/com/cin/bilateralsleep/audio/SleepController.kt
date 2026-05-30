package com.cin.bilateralsleep.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth bridging the Compose UI and the audio service.
 *
 * Parameter writes update both the live [SynthParams] (read by the engine) and a
 * mirrored StateFlow (so the UI recomposes). Start/stop request gentle fades and
 * drive the foreground service. No locks: the engine only ever reads volatiles.
 */
object SleepController {

    val params = SynthParams()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _timerMinutes = MutableStateFlow(0)        // 0 = off
    val timerMinutes: StateFlow<Int> = _timerMinutes.asStateFlow()

    private val _remainingMillis = MutableStateFlow(0L)
    val remainingMillis: StateFlow<Long> = _remainingMillis.asStateFlow()

    // mirrored parameter flows for the UI
    val altRate = MutableStateFlow(params.altRate)
    val crackleDensity = MutableStateFlow(params.crackleDensity)
    val carrierCenter = MutableStateFlow(params.carrierCenter)
    val carrierQ = MutableStateFlow(params.carrierQ)
    val brownLevel = MutableStateFlow(params.brownLevel)
    val panShapeHard = MutableStateFlow(params.panShapeHard)
    val volume = MutableStateFlow(params.volume)
    val binauralHz = MutableStateFlow(params.binauralHz)

    fun setAltRate(v: Double) { params.altRate = v; altRate.value = v }
    fun setCrackleDensity(v: Double) { params.crackleDensity = v; crackleDensity.value = v }
    fun setCarrierCenter(v: Double) { params.carrierCenter = v; carrierCenter.value = v }
    fun setCarrierQ(v: Double) { params.carrierQ = v; carrierQ.value = v }
    fun setBrownLevel(v: Double) { params.brownLevel = v; brownLevel.value = v }
    fun setPanShapeHard(v: Boolean) { params.panShapeHard = v; panShapeHard.value = v }
    fun setVolume(v: Double) { params.volume = v; volume.value = v }
    fun setBinauralHz(v: Double) { params.binauralHz = v; binauralHz.value = v }

    fun setTimerMinutes(minutes: Int) { _timerMinutes.value = minutes }

    /** Apply a full parameter set (preset recall). */
    fun applyParams(p: SynthParams) {
        setAltRate(p.altRate)
        setCrackleDensity(p.crackleDensity)
        setCarrierCenter(p.carrierCenter)
        setCarrierQ(p.carrierQ)
        setBrownLevel(p.brownLevel)
        setPanShapeHard(p.panShapeHard)
        setVolume(p.volume)
        setBinauralHz(p.binauralHz)
    }

    fun snapshot(): SynthParams = SynthParams().also { params.copyInto(it) }

    fun start(ctx: Context) {
        if (_isRunning.value) return
        params.fadeSeconds = 1.5
        params.targetEnvelope = 1.0
        val intent = Intent(ctx, AudioService::class.java).setAction(AudioService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        _isRunning.value = true
    }

    fun stop(ctx: Context) {
        if (!_isRunning.value) return
        params.fadeSeconds = 1.5
        params.targetEnvelope = 0.0
        ctx.startService(Intent(ctx, AudioService::class.java).setAction(AudioService.ACTION_STOP))
        // _isRunning flips to false via onServiceStopped() once the fade completes
    }

    fun toggle(ctx: Context) {
        if (_isRunning.value) stop(ctx) else start(ctx)
    }

    // --- called by the service ---
    internal fun updateRemaining(ms: Long) { _remainingMillis.value = ms }

    internal fun onServiceStopped() {
        _isRunning.value = false
        _remainingMillis.value = 0L
    }
}
