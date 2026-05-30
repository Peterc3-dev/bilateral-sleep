package com.cin.bilateralsleep.audio

/**
 * Live synth parameters shared between the UI thread (writers) and the audio
 * thread (reader). All fields are @Volatile so the engine picks up changes on
 * the next block without locks; nothing here is reseeded per block.
 *
 * Defaults mirror the desktop reference (bilateral_sleep.py).
 */
class SynthParams {
    @Volatile var altRate: Double = 0.7          // Hz, L<->R swing (SWAY)
    @Volatile var crackleDensity: Double = 12.0  // clicks/sec (CRACKLE)
    @Volatile var carrierCenter: Double = 1500.0 // Hz, bandpass center (WARMTH)
    @Volatile var carrierQ: Double = 4.0         // bandpass Q (resonance)
    @Volatile var brownLevel: Double = 0.25      // 0..1 brown bed (rumble)
    @Volatile var panShapeHard: Boolean = false  // cosine (false) / hard (true)
    @Volatile var volume: Double = 0.4           // 0..1 master (VOLUME)
    @Volatile var binauralHz: Double = 0.0       // 0 = off, else beat in Hz

    // Master fade envelope, driven by the controller/service for click-free
    // start/stop and the slow sleep-timer fade-out.
    @Volatile var targetEnvelope: Double = 0.0   // 0 = silent, 1 = full
    @Volatile var fadeSeconds: Double = 1.5      // ramp time to reach the target

    fun copyInto(other: SynthParams) {
        other.altRate = altRate
        other.crackleDensity = crackleDensity
        other.carrierCenter = carrierCenter
        other.carrierQ = carrierQ
        other.brownLevel = brownLevel
        other.panShapeHard = panShapeHard
        other.volume = volume
        other.binauralHz = binauralHz
    }
}
