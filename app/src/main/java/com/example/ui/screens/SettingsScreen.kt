package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WaveTheme
import com.example.ui.theme.*
import com.example.viewmodel.VocalStudioViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: VocalStudioViewModel,
    modifier: Modifier = Modifier
) {
    val currentTheme by viewModel.currentTheme.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("settings_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Upper logo and title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CONTRÔLE DU MATÉRIEL ET DU CONTEXTE",
                    color = primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Configuration du studio",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 1: AUDIO LATENCY PROFILE ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "TAILLE DU BUFFER DSP (OPTIMISATION DE LA LATENCE)",
                    color = primaryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Utilisez ce curseur pour régler la taille du buffer de 0 à 124 en temps réel. Plus la valeur est basse, plus la latence se rapproche de 0 ms (sans délai).",
                    color = LightMutedText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                val bufferSizeState by viewModel.bufferSize.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TAILLE DU BLOC TAMPON :",
                        color = LightMutedText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (bufferSizeState == 0) "0 (mode latence zéro, ~0.2 ms)"
                               else "$bufferSizeState échantillons (${((bufferSizeState * 1000f / 44100f) * 10).roundToInt() / 10f} ms)",
                        color = primaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Slider(
                    value = bufferSizeState.toFloat(),
                    onValueChange = { newVal ->
                        viewModel.setBufferSize(newVal.roundToInt())
                    },
                    valueRange = 0f..124f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = Color.Gray.copy(alpha = 0.2f),
                        thumbColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("buffer_size_fader_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 16, 32, 64, 124).forEach { size ->
                        val isSel = bufferSizeState == size
                        val glowStroke = if (isSel) BorderStroke(1.dp, primaryColor) else BorderStroke(1.dp, Color.Transparent)

                        Surface(
                            onClick = { viewModel.setBufferSize(size) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) primaryColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = glowStroke
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (size == 0) "0 (zéro)" else "$size",
                                    color = if (isSel) primaryColor else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (size == 0) "Direct" else "${((size * 1000f / 44100f) * 10).roundToInt() / 10f}ms",
                                    color = LightMutedText,
                                    fontSize = 7.sp,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 2: STUDIO VISUAL SKINS ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "THÈMES VISUELS DU STUDIO",
                        color = primaryColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Icon(Icons.Default.Build, contentDescription = null, tint = primaryColor, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Changez l'apparence de l'interface du studio selon l'ambiance de votre enregistrement.",
                    color = LightMutedText,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Grid of 6 themes
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val themes = WaveTheme.values()
                    for (i in themes.indices step 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeSelectionCard(
                                theme = themes[i],
                                isSelected = currentTheme == themes[i],
                                onClick = { viewModel.selectTheme(themes[i]) },
                                modifier = Modifier.weight(1f)
                            )
                            if (i + 1 < themes.size) {
                                ThemeSelectionCard(
                                    theme = themes[i + 1],
                                    isSelected = currentTheme == themes[i + 1],
                                    onClick = { viewModel.selectTheme(themes[i + 1]) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- SECTION 3: SYSTEM INFO BLOCK ---
        Text(
            text = "INFORMATIONS SUR LE MATÉRIEL",
            color = LightMutedText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(12.dp)
        ) {
            InfoRow(label = "Version du moteur", value = "Moteur Mahfoud (64 bits stable)")
            InfoRow(label = "Système", value = "Android 8.0+ Oreo (testé sur Android 15)")
            InfoRow(label = "Interface audio numérique", value = "OpenSL ES / AAudio Audio HAL")
            InfoRow(label = "Détection du casque", value = "Casque filaire / profil Bluetooth")
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Bottom Brand Signature Watermark Logo
        BrandSignatureWatermark()

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ThemeSelectionCard(
    theme: WaveTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sampleColor = when(theme) {
        WaveTheme.DARK_NEON -> DarkNeonPrimary
        WaveTheme.CYBER_BLUE -> CyberBluePrimary
        WaveTheme.GOLD_PRO -> GoldProPrimary
        WaveTheme.RED_STUDIO -> RedStudioPrimary
        WaveTheme.PURPLE_GALAXY -> PurpleGalaxyPrimary
        WaveTheme.EMERALD_PRO -> EmeraldProPrimary
    }

    val outlineColor = if (isSelected) sampleColor else Color.White.copy(alpha = 0.08f)

    Surface(
        onClick = onClick,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, outlineColor, RoundedCornerShape(8.dp))
            .testTag("theme_card_${theme.name.lowercase()}"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color indicator pill
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(sampleColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = theme.label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (isSelected) Color.White else LightMutedText
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = LightMutedText, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

// Global Reusable Brand Signature Watermark
@Composable
fun BrandSignatureWatermark(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Mahfoud Audio Labs",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = LightMutedText.copy(alpha = 0.5f),
            letterSpacing = 1.6.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        Text(
            text = "© 2026 Mahfoud • Tous droits réservés",
            fontSize = 7.sp,
            color = Color.Gray.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
