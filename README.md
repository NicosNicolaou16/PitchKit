# Pitch Kit

A modern and easy-to-use Android library for real-time guitar tuning and chord
detection straight from the device microphone. It captures audio, analyzes the
frequency spectrum, and returns the detected note or chord — all with a pure
Kotlin DSP core and **no third-party audio libraries**. First-class Jetpack
Compose support is included out of the box.

Note: The example project does not include examples for all methods.

## 🌟 Features

This library is designed to simplify pitch and chord detection in your Android app
with a robust set of features:

- **🎸 Note Detection**: Detect individual notes (E, A, D, G, B, e, …) with the
  YIN pitch-detection algorithm for accurate, octave-error-resistant results.
- **🎶 Chord Detection**: Recognize chords such as `Am`, `Bb`, `C`, `D`, `E`,
  `Em`, `Em7`, `Am7`, and `Asus` using chroma + template matching.
- **🎚️ Tuning in Cents**: Every detected note reports how flat/sharp it is, ready
  to drive a tuner needle.
- **🎻 Multi-Instrument Ready**: Built-in `InstrumentProfile` presets (Guitar,
  Ukulele, Bass) — or supply your own custom tuning.
- **🔇 Noise Handling**: DC-offset removal, a high-pass filter, an energy gate, and
  temporal smoothing reduce background interference.
- **⚡ Real-Time & Efficient**: A from-scratch FFT and coroutine-based `Flow`
  pipeline keep detection fast and off the main thread.
- **🚀 Jetpack Compose Support**: A single composable handles the microphone
  permission (including the permanently-denied → Settings flow) and streams
  results back to you.
---

## 🤔 Why Use This Library?

- **No Third-Party Audio Dependencies**: The entire signal-processing pipeline
  (FFT, pitch detection, chroma analysis) is implemented from scratch in Kotlin.
- **Time-Saving**: Provides a simple, out-of-the-box solution to microphone
  capture, permission handling, and DSP — saving significant development time.
- **Boilerplate Reduction**: Handles `AudioRecord`, the runtime permission flow,
  and threading, letting you focus on your app's logic.
- **Typed Results**: Returns a clean, typed `TuningResult` you can format, style,
  or drive UI from — not a fixed string.
---