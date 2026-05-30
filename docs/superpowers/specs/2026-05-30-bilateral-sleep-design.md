# Bilateral Sleep — Design Spec

Date: 2026-05-30
Status: Approved (design + foreground-notification caveat accepted)

## Purpose
A real-time bilateral sleep-induction audio generator. A "Jacob's ladder / electric
arc" crackle over a brown-noise bed is swung left<->right by a slow, sub-audible
equal-power pan LFO (the *sleep lever*). Nothing is pre-rendered; audio is synthesised
continuously and can run for hours.

Two deliverables share **one identical DSP algorithm** so they sound the same:
1. `desktop/bilateral_sleep.py` — Linux reference tool (numpy + sounddevice).
2. `android/` — autism-friendly Android app, dimmed-phosphor UI, packaged as an APK
   for the Nothing Phone (2a).

## Architecture: carrier vs envelope (strictly separate)
- **CARRIER** (in-band, what the driver reproduces):
  - Brown-noise bed: leaky-integrated white noise, DC-blocked, scaled by `brown-level`.
  - Crackle: Poisson-distributed impulse clicks (exponential inter-arrival) fed through a
    resonant bandpass (RBJ biquad, `carrier-center`, `carrier-q`). The ringing = the arc.
- **ENVELOPE** (sub-audible, NEVER emitted as a tone):
  - Equal-power (cosine-law) pan LFO at `alt-rate` (default 0.7 Hz) alternates the whole
    carrier L<->R. `theta = (pan+1)*pi/4; L = cos(theta), R = sin(theta)`. Center crossing
    = 0.707/0.707 — no loudness dip. `pan-shape hard` dwells at the extremes via a steeper
    map (tanh), still equal-power crossfaded.
- **Binaural layer** (optional `--binaural-hz N`): faint 200 Hz L / (200+N) Hz R sines,
  per-channel constant (bypass the pan), sit *under* the crackle.

## State continuity (no buffer-boundary artifacts)
All mutable DSP state lives on a persistent engine object; nothing reseeds per buffer:
- RNG: one `numpy.random.Generator`, never reseeded.
- Crackle: `samples_to_next_click` float counter carried across buffers; overlap-add
  convolution `conv_tail` carried so the bandpass ringing crosses boundaries seamlessly.
- Brown bed: leaky-integrator state carried; processed in 128-sample sub-blocks via a
  numerically-stable cumsum identity (numpy-only, exact, no scipy).
- Pan LFO + both binaural oscillators: phase accumulators carried, wrapped mod 2*pi.

## Desktop tool — CLI (exactly as specced)
`--alt-rate 0.7 --crackle-density 12 --carrier-center 1500 --carrier-q 4
--brown-level 0.25 --pan-shape {cosine,hard} --volume 0.4 --samplerate 48000
--binaural-hz <N>` plus convenience `--blocksize 1024 --device <id>`.
Stereo float32, streams to default device, prints effective params on start, graceful
Ctrl-C. DSP factored into `process(frames)->(frames,2)` for headless testing.

## Android app
- **Engine** (`audio/BilateralEngine.kt`): mirrors the Python DSP. JVM runs per-sample
  recursion fine, so the bandpass is a direct biquad recursion (mathematically identical
  to the desktop kernel-convolution). Feeds an `AudioTrack` (PCM_FLOAT, stereo, 48 kHz,
  MODE_STREAM) from a dedicated thread.
- **Foreground service** (`audio/AudioService.kt`) + partial wakelock: survives screen-off
  and runs for hours. OS-mandated notification is **low-importance, silent, no-vibrate,
  ongoing** — the one accepted exception to "no notifications".
- **UI** (Jetpack Compose, Material3, `compileSdk 35`): dimmed-phosphor (near-black bg,
  dim muted green/amber, no glow flicker), fully **static** (no animation, no live motion).
  Main screen: big START/STOP, four plain-language sliders (SWAY / CRACKLE / WARMTH /
  VOLUME, raw value shown small), sleep timer. Advanced (collapsed): resonance (Q),
  rumble (brown level), pan shape, binaural N.
- **Safeguards**: (1) ~1.5 s equal-power gain ramps on every start/stop/volume change —
  never abrupt; (2) sleep timer (off/15/30/45/60/90) with slow fade-to-silence; (3) static
  UI, silent service, no haptics/pings; (4) named presets (save/recall) + built-ins +
  plain-language labels.

## Build & delivery
Debug-signed APK via cached Gradle 8.11.1 + existing `~/.android/debug.keystore` ->
`app-debug.apk`. Phone is on the mesh but Termux sshd is down; deliver via (a) hand the
APK to the user directly, (b) `adb connect` install if wireless debugging is on, or
(c) scp to Termux once `sshd` is started. Auto-push + ping when reachable.

## Verification (post-build)
Adversarial multi-dimension review workflow: Python<->Kotlin DSP parity, buffer-boundary
artifact analysis, equal-power pan correctness, Android service/lifecycle/manifest
correctness, autism-friendly accessibility audit. Each finding independently verified.
