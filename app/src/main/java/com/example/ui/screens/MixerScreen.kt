package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LightMutedText
import com.example.ui.theme.PeakClipRed
import com.example.viewmodel.VocalStudioViewModel
import kotlin.math.roundToInt

@Composable
fun MixerScreen(
    viewModel: VocalStudioViewModel,
    modifier: Modifier = Modifier
) {
    val volInput by viewModel.volInput.collectAsState()
    val volVocal by viewModel.volVocal.collectAsState()
    val volEffect by viewModel.volEffect.collectAsState()
    val volMaster by viewModel.volMaster.collectAsState()

    // Bouncing level states from Audio Thread
    val liveVU by viewModel.audioProcessor.vuLevels.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("mixer_screen")
    ) {
        // Stats header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CONSOLE DE MIXAGE STUDIO",
                    color = primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Curseurs de la console principale",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Icon(
                Icons.Default.Menu,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Faders Deck Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            // FADER 1: INPUT MIC
            VocalFaderColumn(
                label = "ENTRÉE MICRO",
                value = volInput,
                onValueChange = { viewModel.setVolInput(it) },
                liveLevelValue = liveVU.first * 1.1f * volInput,
                primaryColor = primaryColor
            )

            // FADER 2: VOCAL MAIN
            VocalFaderColumn(
                label = "VOIX PRINCIPALE",
                value = volVocal,
                onValueChange = { viewModel.setVolVocal(it) },
                liveLevelValue = liveVU.first * 0.9f * volVocal,
                primaryColor = MaterialTheme.colorScheme.secondary
            )

            // FADER 3: EFFECTS SEND
            VocalFaderColumn(
                label = "ENVOI EFFETS",
                value = volEffect,
                onValueChange = { viewModel.setVolEffect(it) },
                liveLevelValue = liveVU.second * 1.2f * volEffect,
                primaryColor = primaryColor
            )

            // FADER 4: MASTER OUT
            VocalFaderColumn(
                label = "SORTIE MASTER",
                value = volMaster,
                onValueChange = { viewModel.setVolMaster(it) },
                liveLevelValue = (liveVU.first + liveVU.second) * 0.5f * volMaster,
                primaryColor = MaterialTheme.colorScheme.primary,
                isMaster = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun RowScope.VocalFaderColumn(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    liveLevelValue: Float, // Animated peak DB simulation bound
    primaryColor: Color,
    isMaster: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Node title
        Text(
            text = label,
            color = if (isMaster) primaryColor else Color.White,
            fontWeight = if (isMaster) FontWeight.Black else FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(3.dp))
        
        Text(
            text = if (value <= 0.05f) "MUET" else "${(value * 100).roundToInt()}%",
            color = if (value <= 0.05f) PeakClipRed else primaryColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Fader body
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // db Tick markers drawing
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(22.dp)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                listOf("+6", "0", "-6", "-12", "-24", "-∞").forEach { tick ->
                    Text(
                        tick,
                        color = LightMutedText,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Vertical Slider
            CustomVerticalSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..1.5f,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(42.dp)
                    .testTag("fader_${label.lowercase().replace(" ", "_")}"),
                color = primaryColor
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Vertical VU meter
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black)
            ) {
                val animatedHeight by animateFloatAsState(
                    targetValue = liveLevelValue.coerceIn(0.01f, 1.2f),
                    animationSpec = spring(stiffness = 200f)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedHeight.coerceIn(0.01f, 1.0f))
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(PeakClipRed, primaryColor)
                            )
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun CustomVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(valueRange) {
                detectTapGestures(
                    onPress = { offset ->
                        val peakY = size.height.toFloat()
                        if (peakY > 0) {
                            val rawFraction = 1f - (offset.y / peakY)
                            val fraction = rawFraction.coerceIn(0f, 1f)
                            val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                            onValueChange(newValue)
                        }
                    }
                )
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val peakY = size.height.toFloat()
                    if (peakY > 0) {
                        val currentFraction = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
                        val dragFractionDiff = -dragAmount.y / peakY
                        val newFraction = (currentFraction + dragFractionDiff).coerceIn(0f, 1f)
                        val newValue = valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)
                    }
                }
            }
    ) {
        val totalHeight = maxHeight
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Track
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
            )
            // Active Track
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .align(Alignment.BottomCenter)
                    .fillMaxHeight(fraction)
                    .background(color, RoundedCornerShape(2.dp))
            )
            // Thumb
            Box(
                modifier = Modifier
                    .offset(y = totalHeight * (1f - fraction) - totalHeight / 2f)
                    .size(24.dp)
                    .background(Color.White, CircleShape)
                    .border(2.dp, color, CircleShape)
            )
        }
    }
}
