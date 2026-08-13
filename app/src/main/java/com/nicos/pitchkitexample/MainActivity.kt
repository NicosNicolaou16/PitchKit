/*
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
}*/
package com.nicos.pitchkitexample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

                    // Kept exactly as requested
                    GuitarTunerListener { result ->
                        resultNote = result
                    }

                    // The new Material 3 Expressive UI
                    ExpressiveTunerUI(
                        resultNote = resultNote,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun ExpressiveTunerUI(resultNote: String, modifier: Modifier = Modifier) {
    // Standard guitar tuning targets
    val standardStrings = listOf("E", "A", "D", "G", "B", "e")

    // Check if the detected note matches a standard string (ignoring case for top/bottom E)
    // We use .contains in case your library returns notes with octaves like "E2" or "A2"
    val isStandardNote = standardStrings.any { resultNote.contains(it, ignoreCase = true) } && resultNote != "-"

    // Smoothly animate the main note color to green when a target note is detected
    val animatedNoteColor by animateColorAsState(
        targetValue = if (isStandardNote) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
        label = "noteColor"
    )

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {

        Text(
            text = "Pitch Kit",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        // Main Expressive Note Display
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = resultNote,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 120.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = animatedNoteColor,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Standard Strings Indicator Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            standardStrings.forEach { stringNote ->
                // Determine if this specific peg is the one currently being detected
                val isCurrentTarget = resultNote.contains(stringNote, ignoreCase = true) && resultNote != "-"

                // Animate background color
                val backgroundColor by animateColorAsState(
                    targetValue = if (isCurrentTarget) Color(0xFF4CAF50).copy(alpha = 0.2f)
                    else Color.Transparent,
                    label = "pegBg"
                )

                // Animate text color
                val textColor by animateColorAsState(
                    targetValue = if (isCurrentTarget) Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "pegText"
                )

                // Add a slight pop animation when the string is active
                val scale by animateFloatAsState(
                    targetValue = if (isCurrentTarget) 1.2f else 1.0f,
                    label = "pegScale"
                )

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(backgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringNote,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = if (isCurrentTarget) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = textColor
                    )
                }
            }
        }
    }
}