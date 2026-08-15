# Pitch Kit

[![Linktree](https://img.shields.io/badge/linktree-1de9b6?style=for-the-badge&logo=linktree&logoColor=white)](https://linktr.ee/nicos_nicolaou)
[![Static Badge](https://img.shields.io/badge/Site-blue?style=for-the-badge&label=Web)](https://nicosnicolaou16.github.io/)
[![X](https://img.shields.io/badge/X-%23000000.svg?style=for-the-badge&logo=X&logoColor=white)](https://twitter.com/nicolaou_nicos)
[![LinkedIn](https://img.shields.io/badge/linkedin-%230077B5.svg?style=for-the-badge&logo=linkedin&logoColor=white)](https://linkedin.com/in/nicos-nicolaou-a16720aa)
[![Medium](https://img.shields.io/badge/Medium-12100E?style=for-the-badge&logo=medium&logoColor=white)](https://medium.com/@nicosnicolaou)
[![Mastodon](https://img.shields.io/badge/-MASTODON-%232B90D9?style=for-the-badge&logo=mastodon&logoColor=white)](https://androiddev.social/@nicolaou_nicos)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?style=for-the-badge&logo=Bluesky&logoColor=white)](https://bsky.app/profile/nicolaounicos.bsky.social)
[![Dev.to blog](https://img.shields.io/badge/dev.to-0A0A0A?style=for-the-badge&logo=dev.to&logoColor=white)](https://dev.to/nicosnicolaou16)
[![YouTube](https://img.shields.io/badge/YouTube-%23FF0000.svg?style=for-the-badge&logo=YouTube&logoColor=white)](https://www.youtube.com/@nicosnicolaou16)
[![Static Badge](https://img.shields.io/badge/Developer_Profile-blue?style=for-the-badge&label=Google)](https://g.dev/nicolaou_nicos)

A modern and easy-to-use Android library for real-time guitar tuning and chord
detection straight from the device microphone. It captures audio, analyzes the
frequency spectrum, and returns the detected note or chord — all with a pure
Kotlin DSP core and **no third-party audio libraries**. First-class Jetpack
Compose support is included out of the box.

## 🌟 Features

This library is designed to simplify pitch and chord detection in your Android app
with a robust set of features:

- **🎸 Note Detection**: Detect individual notes (E, A, D, G, B, …) with the
  YIN pitch-detection algorithm for accurate, octave-error-resistant results.
- **🎶 Chord Detection**: Recognize chords such as `Am`, `Bb`, `C`, `D`, `E`,
  `Em`, `Em7`, `Am7`, and `Asus2` using chroma + template matching.
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

### 🎸 Preview (Demo)

| Jetpack Compose                                                             |
|-----------------------------------------------------------------------------|
| <video src="examples/example.mp4" width="100%" controls>rewrw</video>       |
| *[Demo project](https://github.com/NicosNicolaou16/PitchKit/tree/main/app)* |
 
---

### 🛠️ Versioning

- **JDK Version**: `17`
- **Target SDK**: `37`
- **Minimum SDK**: `28`
- **Kotlin Version**: `2.4.10`
- **Gradle Version**: `9.3.1`
- **Build Tool Version**: `37`

---

## !! IMPORTANT NOTE

THE BETA RELEASES MAY CONTAIN MAJOR OR MINOR CHANGES.
 
---

## ⚙️ Basic Configuration (Gradle Dependencies)

### Groovy

```groovy
implementation 'com.github.NicosNicolaou16:PitchKit:1.0.0-beta01'
```

```groovy
allprojects {
    repositories {
        maven { url "https://jitpack.io" }
    }
}
```

### Kotlin DSL

```kotlin
implementation("com.github.NicosNicolaou16:PitchKit:1.0.0-beta01")
```

```kotlin
dependencyResolutionManagement {
    //...
    repositories {
        //...
        maven { url = uri("https://jitpack.io") }
    }
}
```

### libs.versions.toml

```toml
[versions]
# other versions here...
pitchKit = "1.0.0-beta01"
 
[libraries]
# other libraries here...
pitch-kit = { group = "com.github.NicosNicolaou16", name = "PitchKit", version.ref = "pitchKit" }
```

```kotlin
implementation(libs.pitch.kit)
```

```kotlin
dependencyResolutionManagement {
    //...
    repositories {
        //...
        maven { url = uri("https://jitpack.io") }
    }
}
```
 
---

## 🚀 Compose Configuration

### Step 1 - Add the Tuner Listener

Drop `GuitarTunerListener` into any composable. It requests the microphone
permission, starts detection, and streams a typed `TuningResult` back through the
`onResult` callback. Detection stops automatically when the composable leaves the
composition.

```kotlin
@Composable
fun TunerScreen() {
    var resultNote by remember { mutableStateOf("-") }
 
    GuitarTunerListener { result ->
        resultNote = when (result) {
            is TuningResult.Note -> result.name
            is TuningResult.Chord -> result.name
            TuningResult.Silence -> "-"
        }
    }
 
    Text(text = resultNote)
}
```

### Step 2 - Use the Typed Result

`TuningResult` is a sealed class, so you can handle each case exhaustively and use
the numeric data (frequency, cents) however you like:

```kotlin
GuitarTunerListener { result ->
    when (result) {
        is TuningResult.Note -> {
            // result.name  -> "E", "A#", ...
            // result.freq  -> detected frequency in Hz
            // result.cents -> deviation from perfect pitch (-50..+50)
        }
        is TuningResult.Chord -> {
            // result.name  -> "Am", "Cmaj7", ...
        }
        TuningResult.Silence -> {
            // no sound / below threshold
        }
    }
}
```

### Step 3 - Choose an Instrument (Optional)

By default the tuner is configured for a standard 6-string guitar. Pass a
different `InstrumentProfile` to tune another instrument:

```kotlin
// Built-in presets: InstrumentProfile.Guitar (default), .Ukulele, .Bass
GuitarTunerListener(profile = InstrumentProfile.Ukulele) { result ->
    //...your code here
}
```

You can also supply your own profile for custom tunings:

```kotlin
val dropD = InstrumentProfile(
    name = "Drop D",
    minFreq = 60.0,
    maxFreq = 5000.0,
    bassCeiling = 400.0,
    harmonicPivot = 200.0,
    openStrings = listOf(
        InstrumentProfile.OpenString("D2", 73.42),
        InstrumentProfile.OpenString("A2", 110.00),
        InstrumentProfile.OpenString("D3", 146.83),
        InstrumentProfile.OpenString("G3", 196.00),
        InstrumentProfile.OpenString("B3", 246.94),
        InstrumentProfile.OpenString("E4", 329.63),
    ),
)
 
GuitarTunerListener(profile = dropD) { result ->
    //...your code here
}
```

### Step 4 - Customize the Permission Popup (Optional)

All popup strings are parameters, so you can localize or rebrand them:

```kotlin
GuitarTunerListener(
    titleText = "Microphone needed",
    rationaleText = "This app needs microphone access to detect notes and chords.",
    permanentlyDeniedText = "Microphone access is blocked. Please enable it in Settings.",
    allowText = "Allow",
    openSettingsText = "Open Settings",
    dismissText = "Not now",
    onResult = { result ->
        //...your code here
    }
)
```

### Example for Compose Implementation

```kotlin
@Composable
fun PitchKitDemo() {
    var resultNote by remember { mutableStateOf("-") }
    var resultFreq by remember { mutableDoubleStateOf(0.0) }
 
    // ==========================================
    // 1. Start the tuner
    // ==========================================
    GuitarTunerListener(profile = InstrumentProfile.Guitar) { result ->
        when (result) {
            is TuningResult.Note -> resultFreq = result.freq.toDouble()
            else -> resultFreq = 0.0 // clear freq on chord/silence
        }
        resultNote = when (result) {
            is TuningResult.Note -> result.name
            is TuningResult.Chord -> result.name
            TuningResult.Silence -> "-"
        }
    }
 
    // ==========================================
    // 2. Render your UI
    // ==========================================
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = resultNote)
        if (resultFreq > 0) {
            Text(text = "${"%.1f".format(resultFreq)} Hz")
        }
    }
}
```
 
---

## 🎼 Supported Chords

Each of the 12 roots is combined with the following qualities:

`major`, `minor` (`m`), `dominant 7th` (`7`), `minor 7th` (`m7`), `major 7th`
(`maj7`), `sus2`, `sus4`, `diminished` (`dim`), and `augmented` (`aug`).

Examples: `C`, `Am`, `G7`, `Em7`, `Dmaj7`, `Asus2`, `Bdim`, `Faug`.
 
---

## ⭐ Stargazers

If you enjoy this project, please give it a star!
Check out all the stargazers
here: [Stargazers on GitHub](https://github.com/NicosNicolaou16/PitchKit/stargazers)
 
---

## 🙏 Support & Contributions

This library is actively maintained. Feedback, bug reports, and feature requests
are welcome! Please feel free to **open an issue** or submit a **pull request**.