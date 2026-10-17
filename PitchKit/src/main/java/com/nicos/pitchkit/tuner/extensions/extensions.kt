package com.nicos.pitchkit.tuner.extensions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.nicos.pitchkit.tuner.TunerEngine
import com.nicos.pitchkit.tuner.TuningResult

// Internal: bridges the private engine result to the public API type.
internal fun TunerEngine.Result.toPublic(): TuningResult = when (this) {
    is TunerEngine.Result.Note -> TuningResult.Note(name = name, cents = cents, freq = freq)
    is TunerEngine.Result.Chord -> TuningResult.Chord(name = name)
    TunerEngine.Result.Silence -> TuningResult.Silence
}

/**
 * LocalContext is often a ContextWrapper around the Activity (Hilt, themed wrappers,
 * some navigation setups), so a plain `as? Activity` returns null.
 * Walk down the baseContext chain until the Activity is found.
 */
internal fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null   // not hosted in an Activity (rare: e.g. a Service-driven overlay)
}