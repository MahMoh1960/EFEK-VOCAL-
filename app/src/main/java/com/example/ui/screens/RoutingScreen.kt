package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LightMutedText
import com.example.viewmodel.VocalStudioViewModel
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RoutingScreen(
    viewModel: VocalStudioViewModel,
    modifier: Modifier = Modifier
) {
    val effects by viewModel.effectsState.collectAsState()
    val isInputActive by viewModel.isInputActive.collectAsState()

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    // Animated phase for flowing electrons cable line effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("routing_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Upper Intro
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MATRICE DE ROUTAGE DES RACKS NUMÉRIQUES",
                    color = primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Flux de routage du signal studio",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Icon(
                Icons.Default.Share,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Touchez un module ci-dessous pour l'activer ou le mettre en bypass en direct. Le signal audio néon indique une activité DSP en cours.",
            color = LightMutedText,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Large Flexible Diagram Layout
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
        ) {
            // Draw visual connection wire cables first (background)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Node coordinates mapping
                val nodeInputMic = Offset(w * 0.15f, h * 0.50f)
                val nodeInputSum = Offset(w * 0.32f, h * 0.50f)
                
                val nodeRack1 = Offset(w * 0.55f, h * 0.25f) // EQ + NR Group
                val nodeRack2 = Offset(w * 0.55f, h * 0.50f) // AutoTune + Harmony Group
                val nodeRack3 = Offset(w * 0.55f, h * 0.75f) // Reverb / Delay Group

                val nodeMixerSum = Offset(w * 0.78f, h * 0.50f)
                val nodeOutputSys = Offset(w * 0.90f, h * 0.50f)

                // Render Cable 1: Mic -> Sum Node
                drawWireCable(
                    start = nodeInputMic,
                    end = nodeInputSum,
                    isActive = isInputActive,
                    pulsePhase = pulsePhase,
                    color = primaryColor
                )

                // Routing sum splits to the 3 major processing clusters standard flow
                val systemAudioActive = isInputActive

                drawWireCable(
                    start = nodeInputSum,
                    end = nodeRack1,
                    isActive = systemAudioActive && effects.eq.isEnabled,
                    pulsePhase = pulsePhase,
                    color = primaryColor
                )
                drawWireCable(
                    start = nodeInputSum,
                    end = nodeRack2,
                    isActive = systemAudioActive && effects.pitchCorrection.isEnabled,
                    pulsePhase = pulsePhase,
                    color = secondaryColor
                )
                drawWireCable(
                    start = nodeInputSum,
                    end = nodeRack3,
                    isActive = systemAudioActive && effects.reverb.isEnabled,
                    pulsePhase = pulsePhase,
                    color = primaryColor
                )

                // From Rack clusters summing to Console Mixer
                drawWireCable(
                    start = nodeRack1,
                    end = nodeMixerSum,
                    isActive = systemAudioActive && effects.eq.isEnabled,
                    pulsePhase = pulsePhase,
                    color = primaryColor
                )
                drawWireCable(
                    start = nodeRack2,
                    end = nodeMixerSum,
                    isActive = systemAudioActive && effects.pitchCorrection.isEnabled,
                    pulsePhase = pulsePhase,
                    color = secondaryColor
                )
                drawWireCable(
                    start = nodeRack3,
                    end = nodeMixerSum,
                    isActive = systemAudioActive && effects.reverb.isEnabled,
                    pulsePhase = pulsePhase,
                    color = primaryColor
                )

                // From Console sum -> Final output system
                drawWireCable(
                    start = nodeMixerSum,
                    end = nodeOutputSys,
                    isActive = systemAudioActive,
                    pulsePhase = pulsePhase,
                    color = primaryColor
                )
            }

            // Lay Interactive buttons over node coordinates for complete tactile experience
            // 1. INPUT NODES
            NodeButton(
                title = "ENTRÉE MICRO",
                isActive = isInputActive,
                onClick = { viewModel.toggleInputActive() },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 10.dp, y = 0.dp),
                color = primaryColor
            )

            // 2. DSP SYSTEM PROCESS CORES GATES
            NodeButton(
                title = "ÉGAL. & BRUIT",
                isActive = effects.eq.isEnabled,
                onClick = { viewModel.updateEffects { state -> state.eq.isEnabled = !state.eq.isEnabled } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = 10.dp, y = 25.dp),
                color = primaryColor
            )

            NodeButton(
                title = "TUNER / HARMONIE",
                isActive = effects.pitchCorrection.isEnabled,
                onClick = { viewModel.updateEffects { state -> state.pitchCorrection.isEnabled = !state.pitchCorrection.isEnabled } },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 10.dp, y = 0.dp),
                color = secondaryColor
            )

            NodeButton(
                title = "RÉVERB. / ÉCHO",
                isActive = effects.reverb.isEnabled,
                onClick = { viewModel.updateEffects { state -> state.reverb.isEnabled = !state.reverb.isEnabled } },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = 10.dp, y = (-25).dp),
                color = primaryColor
            )

            // 3. MASTER OUTPUT NODE
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SORTIE SYSTÈME", color = primaryColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("PISTE STÉRÉO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Watermark / Info indicator block
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Signal acheminé en stéréo PCM 16 bits, en temps réel.",
                color = LightMutedText,
                fontSize = 11.sp
            )
        }
    }
}

// Drawing glowing bezier curve cables with optional pulse animation
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWireCable(
    start: Offset,
    end: Offset,
    isActive: Boolean,
    pulsePhase: Float,
    color: Color
) {
    val cablePath = Path().apply {
        moveTo(start.x, start.y)
        // Draw a nice soft cubic curve between nodes
        cubicTo(
            (start.x + end.x) / 2f, start.y,
            (start.x + end.x) / 2f, end.y,
            end.x, end.y
        )
    }

    // Draw background thick cable sleeve
    drawPath(
        path = cablePath,
        color = if (isActive) Color.DarkGray else Color.LightGray.copy(alpha = 0.1f),
        style = Stroke(width = if (isActive) 6f else 3f)
    )

    if (isActive) {
        // Draw active colored copper wire core
        drawPath(
            path = cablePath,
            color = color.copy(alpha = 0.5f),
            style = Stroke(width = 3.5f)
        )

        // Draw pulsing laser energy traveling along path
        drawPath(
            path = cablePath,
            brush = Brush.linearGradient(
                colors = listOf(Color.White, color, Color.Transparent),
                start = Offset(start.x + (end.x - start.x) * pulsePhase - 40f, start.y + (end.y - start.y) * pulsePhase - 40f),
                end = Offset(start.x + (end.x - start.x) * pulsePhase + 40f, start.y + (end.y - start.y) * pulsePhase + 40f)
            ),
            style = Stroke(width = 4f)
        )
    }
}


@Composable
fun NodeButton(
    title: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(94.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, if (isActive) color else Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .testTag("node_${title.lowercase().replace(" ", "_")}"),
        color = if (isActive) color.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.4f)
    ) {
        Text(
            text = title,
            color = if (isActive) color else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }
}
