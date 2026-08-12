package com.nicos.pitchkit

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nicos.pitchkit.tuner.TunerEngine
import com.nicos.pitchkit.ui.theme.PitchKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val engine = remember {
                        TunerEngine(
                            rmsGate = 0.02        // ignore quiet sounds  ← start here
                        )
                    }
                    var resultNote by remember { mutableStateOf("-") }

                    LaunchedEffect(engine) {
                        engine.start().collect { result ->
                            resultNote = when (result) {
                                is TunerEngine.Result.Note ->
                                    "${result.name} ${result.freq} (${"%.0f".format(result.cents)}¢)"

                                is TunerEngine.Result.Chord -> result.name
                                TunerEngine.Result.Silence -> "—"
                            }
                            Log.d("AudioTuner", resultNote)
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(resultNote)
                    }

                    DisposableEffect(
                        engine
                    ) {
                        onDispose {
                            engine.stop()
                        }
                    }
                }
            }
        }
    }
}