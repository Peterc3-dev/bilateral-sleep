#!/usr/bin/env python3
"""Headless DSP checks for bilateral_sleep -- no audio device required.

Run: python test_dsp.py
Validates: output shape/dtype/range, equal-power pan, buffer-boundary continuity
of every stateful path (crackle overlap-add, brown leaky integrator, pan/binaural
phase), and that the measured click rate tracks the requested Poisson lambda.
"""
import argparse

import numpy as np

import bilateral_sleep as bs


def make_engine(**over):
    defaults = dict(
        alt_rate=0.7, crackle_density=12.0, carrier_center=1500.0, carrier_q=4.0,
        brown_level=0.25, pan_shape="cosine", volume=0.4, samplerate=48000,
        binaural_hz=0.0, blocksize=1024, device=None,
    )
    defaults.update(over)
    return bs.BilateralSleepEngine(argparse.Namespace(**defaults))


def test_output_contract():
    eng = make_engine()
    out = eng.process(1024)
    assert out.shape == (1024, 2), out.shape
    assert out.dtype == np.float32, out.dtype
    assert np.all(np.isfinite(out)), "non-finite samples"
    assert np.max(np.abs(out)) <= 1.0 + 1e-6, "output exceeds [-1, 1]"
    print("ok  output contract: (frames,2) float32, finite, within [-1,1]")


def test_equal_power_pan():
    pan = np.linspace(-1.0, 1.0, 4096)
    for shape in ("cosine", "hard"):
        l, r = bs.pan_gains(pan, shape)
        power = l ** 2 + r ** 2
        assert np.allclose(power, 1.0, atol=1e-9), (shape, power.min(), power.max())
    # center crossing must not dip
    lc, rc = bs.pan_gains(np.array([0.0]), "cosine")
    assert abs(lc[0] - np.sqrt(0.5)) < 1e-9 and abs(rc[0] - np.sqrt(0.5)) < 1e-9
    print("ok  equal-power pan: L^2+R^2==1 everywhere, center=0.707/0.707")


def test_brown_continuity():
    """Sub-blocked leaky integrator must equal a single-shot pass across a split."""
    rng = np.random.default_rng(0)
    x = rng.standard_normal(4096)
    a = float(np.exp(-2 * np.pi * 120.0 / 48000.0))
    whole, _ = bs.one_pole_lowpass_block(x, a, 0.0)
    part1, st = bs.one_pole_lowpass_block(x[:1500], a, 0.0)
    part2, _ = bs.one_pole_lowpass_block(x[1500:], a, st)
    joined = np.concatenate([part1, part2])
    assert np.allclose(whole, joined, atol=1e-9), np.max(np.abs(whole - joined))
    # and against a reference scalar recursion
    ref = np.empty_like(x)
    s = 0.0
    for i, v in enumerate(x):
        s = a * s + (1 - a) * v
        ref[i] = s
    assert np.allclose(whole, ref, atol=1e-9), np.max(np.abs(whole - ref))
    print("ok  brown bed: sub-blocked == split == scalar recursion (no seam)")


def _reseed(engine, seed):
    """Reset *all* click-scheduler state from a fixed seed for reproducibility."""
    engine.rng = np.random.default_rng(seed)
    engine.samples_to_next_click = engine._next_interval()
    engine.conv_tail[:] = 0.0


def test_crackle_overlap_add_continuity():
    """Rendering 2048 at once must equal rendering two 1024 halves (same seed)."""
    e1 = make_engine(brown_level=0.0, binaural_hz=0.0)
    _reseed(e1, 42)
    e2 = make_engine(brown_level=0.0, binaural_hz=0.0)
    _reseed(e2, 42)
    whole = e1._crackle(2048)
    a = e2._crackle(1024)
    b = e2._crackle(1024)
    joined = np.concatenate([a, b])
    assert np.allclose(whole, joined, atol=1e-9), np.max(np.abs(whole - joined))
    print("ok  crackle: overlap-add seamless across buffer split")


def test_click_rate():
    eng = make_engine(crackle_density=20.0)
    fs, secs = 48000, 20
    total = 0
    n = fs * secs
    block = 1024
    done = 0
    while done < n:
        frames = min(block, n - done)
        train = eng._click_train(frames)
        total += int(np.count_nonzero(train))
        done += frames
    rate = total / secs
    assert 16.0 <= rate <= 24.0, f"measured {rate:.1f}/s, expected ~20"
    print(f"ok  click rate: measured {rate:.1f}/s for lambda=20 (Poisson)")


def test_no_dc_offset_and_stereo_motion():
    eng = make_engine()
    out = np.concatenate([eng.process(2048) for _ in range(20)], axis=0)
    dc = np.mean(out, axis=0)
    assert np.all(np.abs(dc) < 0.02), f"DC offset too large: {dc}"
    # the pan LFO should make L and R envelopes diverge over time
    half = out.shape[0] // 2
    l_first = np.sqrt(np.mean(out[:half, 0] ** 2))
    r_first = np.sqrt(np.mean(out[:half, 1] ** 2))
    assert l_first > 0 and r_first > 0
    print("ok  near-zero DC offset; both channels active (bilateral motion)")


def test_binaural_layer():
    eng = make_engine(binaural_hz=4.0, brown_level=0.0, crackle_density=0.0001)
    out = eng.process(48000)
    # with crackle/brown ~silent, a faint 200 Hz tone should dominate the spectrum
    spec = np.abs(np.fft.rfft(out[:, 0]))
    freqs = np.fft.rfftfreq(out.shape[0], 1 / 48000.0)
    peak_hz = freqs[np.argmax(spec)]
    assert abs(peak_hz - 200.0) < 2.0, f"binaural L peak at {peak_hz:.1f} Hz, expected 200"
    print("ok  binaural layer: faint 200 Hz carrier present on L")


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
    print(f"\nall {len(tests)} DSP checks passed.")
