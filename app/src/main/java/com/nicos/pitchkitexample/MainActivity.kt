package com.nicos.pitchkitexample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nicos.pitchkit.tuner.GuitarTunerListener
import com.nicos.pitchkitexample.ui.theme.PitchKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchKitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var resultNote by remember { mutableStateOf("-") }

                    GuitarTunerListener { result ->
                        resultNote = result
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(resultNote)
                    }
                }
            }
        }
    }
}