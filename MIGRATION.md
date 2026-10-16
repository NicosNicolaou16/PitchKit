## 🚚 Migration Guide

### 1.0.1 → 1.0.2

> [!NOTE]
> `GuitarTunerListener` is **deprecated** and renamed to `PitchKitListener`.
> The old name still works in 1.0.2 but will be removed in a future version.
> In Android Studio, place the cursor on the call and press Alt+Enter → "Replace with PitchKitListener".

```kotlin
// Before
GuitarTunerListener(profile = InstrumentProfile.Guitar) { result -> ... }

// After — same parameters, same behaviour
PitchKitListener(profile = InstrumentProfile.Guitar) { result -> ... }
```

**New:** optional `onError: (Throwable) -> Unit`, called if the microphone can't be used.