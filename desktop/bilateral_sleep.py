#!/usr/bin/env python3
"""Bilateral sleep-induction audio synth.

A "Jacob's ladder / electric arc" crackle (Poisson clicks through a resonant
bandpass) layered over a brown-noise bed, swung left<->right by a slow,
sub-audible equal-power pan LFO (the sleep lever). Everything is generated live
in the sounddevice callback -- nothing is pre-rendered -- so it can run for hours.

Carrier (in-band, what the driver reproduces):  crackle + brown bed.
Envelope (sub-audible, never a tone):           equal-power L<->R pan LFO.

Dependencies: numpy + sounddevice only. The IIR filtering is done with
numpy-native techniques (overlap-add convolution + a sub-blocked leaky
integrator) so all state crosses buffer boundaries exactly, with no clicks at
the seams and no per-buffer reseeding.
"""

from __future__ import annotations

import argparse
import signal
import sys
import threading

import numpy as np

try:
    import sounddevice as sd
except OSError as exc:  # pragma: no cover - only hit when PortAudio is missing
    sd = None
    _SD_IMPORT_ERROR = exc
else:
    _SD_IMPORT_ERROR = None


# --------------------------------------------------------------------------- #
# DSP primitives                                                              #
# --------------------------------------------------------------------------- #
def rbj_bandpass(center: float, q: float, fs: float) -> tuple[np.ndarray, np.ndarray]:
    """RBJ constant-0dB-peak bandpass biquad. Returns (b, a) coefficients."""
    w0 = 2.0 * np.pi * center / fs
    cos_w0 = np.cos(w0)
    alpha = np.sin(w0) / (2.0 * q)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0, a1, a2 = 1.0 + alpha, -2.0 * cos_w0, 1.0 - alpha
    b = np.array([b0, b1, b2], dtype=np.float64) / a0
    a = np.array([1.0, a1 / a0, a2 / a0], dtype=np.float64)
    return b, a


def biquad_impulse_response(b: np.ndarray, a: np.ndarray, length: int) -> np.ndarray:
    """Impulse response of a biquad, via a one-time direct-form-II recursion.

    Used to precompute the convolution kernel for the click path. Run once at
    init, so a plain Python loop here is fine.
    """
    h = np.zeros(length, dtype=np.float64)
    z1 = z2 = 0.0
    for n in range(length):
        x = 1.0 if n == 0 else 0.0
        y = b[0] * x + z1
        z1 = b[1] * x - a[1] * y + z2
        z2 = b[2] * x - a[2] * y
        h[n] = y
    return h


def one_pole_lowpass_block(
    x: np.ndarray, a: float, state: float, sub: int = 128
) -> tuple[np.ndarray, float]:
    """Leaky integrator y[n] = a*y[n-1] + (1-a)*x[n], stateful and numpy-native.

    Processed in small sub-blocks using the closed-form
        y[n] = a^(n+1)*y_prev + (1-a) * sum_{k<=n} a^(n-k) x[k]
    evaluated as  a^(n+1)*y_prev + (1-a)*a^n * cumsum(x * a^-k).
    Keeping the sub-block short bounds a^-k so float64 stays well-conditioned,
    while remaining fully vectorised (no per-sample Python loop).
    """
    n = x.shape[0]
    if n == 0:
        return x.copy(), state
    out = np.empty(n, dtype=np.float64)
    one_minus_a = 1.0 - a
    for start in range(0, n, sub):
        blk = x[start : start + sub]
        m = blk.shape[0]
        k = np.arange(m)
        a_pow = a ** k                      # a^0 .. a^(m-1)
        a_neg = a ** (-k)                   # bounded because sub is small
        csum = np.cumsum(blk * a_neg)
        y = a * a_pow * state + one_minus_a * a_pow * csum
        out[start : start + m] = y
        state = y[-1]
    return out, state


def pan_gains(pan: np.ndarray, shape: str) -> tuple[np.ndarray, np.ndarray]:
    """Equal-power (cosine-law) pan. pan in [-1, 1]; returns (left, right) gains.

    L^2 + R^2 == 1 for every value, so center crossings never dip in loudness.
    'hard' steepens the pan curve so it dwells at the extremes, but the gain law
    stays equal-power.
    """
    if shape == "hard":
        pan = np.tanh(3.0 * pan) / np.tanh(3.0)
    theta = (pan + 1.0) * (np.pi / 4.0)     # maps [-1,1] -> [0, pi/2]
    return np.cos(theta), np.sin(theta)


# --------------------------------------------------------------------------- #
# Engine                                                                      #
# --------------------------------------------------------------------------- #
class BilateralSleepEngine:
    """Stateful synth. `process(frames)` returns an (frames, 2) float32 block."""

    KERNEL_LEN = 512        # bandpass ringing decays well within this
    BROWN_POLE_HZ = 120.0   # leaky-integrator corner for the rumble bed
    CLICK_PEAK = 0.7        # per-click ring amplitude (pre brown/volume mix)
    BINAURAL_BASE_HZ = 200.0
    BINAURAL_AMP = 0.06

    def __init__(self, args: argparse.Namespace) -> None:
        self.fs = float(args.samplerate)
        self.alt_rate = float(args.alt_rate)
        self.density = float(args.crackle_density)
        self.center = float(args.carrier_center)
        self.q = float(args.carrier_q)
        self.brown_level = float(args.brown_level)
        self.pan_shape = args.pan_shape
        self.volume = float(args.volume)
        self.binaural_hz = float(args.binaural_hz) if args.binaural_hz else 0.0

        self.rng = np.random.default_rng()   # persistent; never reseeded

        # --- crackle path: precompute the bandpass impulse-response kernel ---
        b, a = rbj_bandpass(self.center, self.q, self.fs)
        h = biquad_impulse_response(b, a, self.KERNEL_LEN)
        peak = np.max(np.abs(h))
        self.kernel = (h / peak) * self.CLICK_PEAK if peak > 0 else h
        self.conv_tail = np.zeros(self.KERNEL_LEN - 1, dtype=np.float64)
        self.samples_to_next_click = self._next_interval()

        # --- brown bed ---
        self.brown_a = float(np.exp(-2.0 * np.pi * self.BROWN_POLE_HZ / self.fs))
        # normalise leaky-integrator output to ~unit RMS, then to bed level 0.3
        self.brown_norm = np.sqrt((1.0 + self.brown_a) / (1.0 - self.brown_a)) * 0.3
        self.brown_state = 0.0

        # --- envelope + binaural phases ---
        self.pan_phase = 0.0
        self.bin_phase_l = 0.0
        self.bin_phase_r = 0.0

    # ------------------------------------------------------------------ #
    def _next_interval(self) -> float:
        lam = max(self.density, 1e-9)
        return float(self.rng.exponential(self.fs / lam))

    def _click_train(self, frames: int) -> np.ndarray:
        """Sparse impulse train for this block; scheduler state carries over."""
        train = np.zeros(frames, dtype=np.float64)
        pos = self.samples_to_next_click
        while pos < frames:
            idx = int(pos)
            # randomised amplitude + polarity for natural, non-mechanical crackle
            amp = self.rng.uniform(0.6, 1.0) * (1.0 if self.rng.random() < 0.5 else -1.0)
            train[idx] += amp
            pos += self._next_interval()
        self.samples_to_next_click = pos - frames
        return train

    def _crackle(self, frames: int) -> np.ndarray:
        """Overlap-add convolution of the click train with the bandpass kernel."""
        train = self._click_train(frames)
        conv = np.convolve(train, self.kernel)          # len frames + K - 1
        out = conv[:frames].copy()
        tail_len = self.conv_tail.shape[0]
        overlap = min(tail_len, frames)
        out[:overlap] += self.conv_tail[:overlap]
        if tail_len > frames:
            # carry the still-unspent part of the previous tail forward too
            leftover = self.conv_tail[frames:]
            new_tail = conv[frames:]
            new_tail[: leftover.shape[0]] += leftover
            self.conv_tail = new_tail
        else:
            self.conv_tail = conv[frames:]
        return out

    def _brown(self, frames: int) -> np.ndarray:
        white = self.rng.standard_normal(frames)
        y, self.brown_state = one_pole_lowpass_block(white, self.brown_a, self.brown_state)
        return y * self.brown_norm * self.brown_level

    def _pan(self, frames: int) -> tuple[np.ndarray, np.ndarray]:
        inc = 2.0 * np.pi * self.alt_rate / self.fs
        phase = self.pan_phase + inc * np.arange(1, frames + 1)
        self.pan_phase = float(phase[-1] % (2.0 * np.pi))
        return pan_gains(np.sin(phase), self.pan_shape)

    def _binaural(self, frames: int) -> tuple[np.ndarray, np.ndarray]:
        inc_l = 2.0 * np.pi * self.BINAURAL_BASE_HZ / self.fs
        inc_r = 2.0 * np.pi * (self.BINAURAL_BASE_HZ + self.binaural_hz) / self.fs
        ph_l = self.bin_phase_l + inc_l * np.arange(1, frames + 1)
        ph_r = self.bin_phase_r + inc_r * np.arange(1, frames + 1)
        self.bin_phase_l = float(ph_l[-1] % (2.0 * np.pi))
        self.bin_phase_r = float(ph_r[-1] % (2.0 * np.pi))
        return self.BINAURAL_AMP * np.sin(ph_l), self.BINAURAL_AMP * np.sin(ph_r)

    # ------------------------------------------------------------------ #
    def process(self, frames: int) -> np.ndarray:
        """Render one block. Returns (frames, 2) float32 in [-1, 1]."""
        source = self._crackle(frames) + self._brown(frames)   # mono carrier
        left_gain, right_gain = self._pan(frames)
        left = source * left_gain
        right = source * right_gain
        if self.binaural_hz > 0.0:
            bl, br = self._binaural(frames)
            left = left + bl
            right = right + br
        out = self.volume * np.stack([left, right], axis=1)
        np.clip(out, -1.0, 1.0, out=out)
        return out.astype(np.float32)

    # sounddevice callback wrapper -------------------------------------- #
    def callback(self, outdata, frames, time, status):  # noqa: ANN001
        if status:
            print(status, file=sys.stderr)
        outdata[:] = self.process(frames)


# --------------------------------------------------------------------------- #
# CLI                                                                         #
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Real-time bilateral sleep-induction audio synth.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--alt-rate", type=float, default=0.7, help="Hz, L<->R swing")
    p.add_argument("--crackle-density", type=float, default=12.0, help="clicks/sec (Poisson lambda)")
    p.add_argument("--carrier-center", type=float, default=1500.0, help="Hz, bandpass center")
    p.add_argument("--carrier-q", type=float, default=4.0, help="bandpass Q")
    p.add_argument("--brown-level", type=float, default=0.25, help="0-1 brown-bed level")
    p.add_argument("--pan-shape", choices=("cosine", "hard"), default="cosine")
    p.add_argument("--volume", type=float, default=0.4, help="0-1 master volume")
    p.add_argument("--samplerate", type=int, default=48000)
    p.add_argument("--binaural-hz", type=float, default=0.0,
                   help="N>0 adds a faint N-Hz binaural beat (200 L / 200+N R)")
    p.add_argument("--blocksize", type=int, default=1024, help="frames per callback")
    p.add_argument("--device", default=None, help="output device (sounddevice id or name)")
    return p


def print_params(args: argparse.Namespace) -> None:
    binaural = f"{args.binaural_hz:g} Hz beat" if args.binaural_hz else "off"
    print("bilateral sleep synth")
    print("---------------------")
    print(f"  samplerate     : {args.samplerate} Hz")
    print(f"  alt-rate (sway): {args.alt_rate:g} Hz  ({args.pan_shape} pan)")
    print(f"  crackle density: {args.crackle_density:g} clicks/s")
    print(f"  carrier        : {args.carrier_center:g} Hz, Q={args.carrier_q:g}")
    print(f"  brown level    : {args.brown_level:g}")
    print(f"  volume         : {args.volume:g}")
    print(f"  binaural       : {binaural}")
    print(f"  blocksize      : {args.blocksize} frames")
    print("  Ctrl-C to stop.\n")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if sd is None:
        print(f"sounddevice/PortAudio unavailable: {_SD_IMPORT_ERROR}", file=sys.stderr)
        return 1

    engine = BilateralSleepEngine(args)
    print_params(args)

    stop = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stop.set())

    try:
        with sd.OutputStream(
            samplerate=args.samplerate,
            blocksize=args.blocksize,
            channels=2,
            dtype="float32",
            device=args.device,
            callback=engine.callback,
        ):
            stop.wait()
    except KeyboardInterrupt:
        pass
    except Exception as exc:  # pragma: no cover
        print(f"audio error: {exc}", file=sys.stderr)
        return 1
    print("\nstopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
