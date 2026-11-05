package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioExporter
import com.example.audio.AudioFileBridge
import com.example.viewmodel.VocalStudioViewModel
import kotlinx.coroutines.launch

@Composable
private fun OptionChips(
    label: String,
    options: List<Int>,
    selected: Int,
    suffix: String,
    onSelect: (Int) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(96.dp))
        options.forEach { v ->
            val sel = selected == v
            Surface(
                onClick = { onSelect(v) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                color = if (sel) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (sel) accent else Color.Transparent)
            ) {
                Text(
                    text = if (v == 0) "Aucun" else "$v $suffix",
                    color = if (sel) accent else Color.White,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun AudioImportPanel(viewModel: VocalStudioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = viewModel.audioProcessor

    val fileName by AudioFileBridge.fileName.collectAsState()
    val playing by AudioFileBridge.playing.collectAsState()
    val progress by AudioFileBridge.progress.collectAsState()
    val loading by AudioFileBridge.loading.collectAsState()
    val message by AudioFileBridge.message.collectAsState()
    val duration by AudioFileBridge.durationSec.collectAsState()
    var loop by remember { mutableStateOf(AudioFileBridge.loopEnabled) }

    val exporting by AudioExporter.exporting.collectAsState()
    val exportProgress by AudioExporter.progress.collectAsState()
    val exportMessage by AudioExporter.message.collectAsState()
    var format by remember { mutableStateOf(AudioExporter.Format.WAV) }
    var bitrate by remember { mutableStateOf(192) }
    var normalize by remember { mutableStateOf(false) }
    var fadeIn by remember { mutableStateOf(0) }
    var fadeOut by remember { mutableStateOf(0) }

    val isRecording by processor.isRecording.collectAsState()
    val recordFrames by processor.recordFrames.collectAsState()
    val recSeconds = recordFrames / processor.sampleRate
    val hasRecording = recordFrames > 0 && !isRecording

    val accent = MaterialTheme.colorScheme.primary

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { AudioFileBridge.load(context, uri) }
        }
    }

    val onSavePicked: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            scope.launch { AudioExporter.saveTo(context, uri) }
        } else {
            AudioExporter.discard()
        }
    }
    val wavSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { onSavePicked(it) }
    val m4aSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { onSavePicked(it) }
    val mp3Saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { onSavePicked(it) }
    val launchSaver: (AudioExporter.Format, String) -> Unit = { f, name ->
        when (f) {
            AudioExporter.Format.WAV -> wavSaver.launch(name)
            AudioExporter.Format.M4A -> m4aSaver.launch(name)
            AudioExporter.Format.MP3 -> mp3Saver.launch(name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag("audio_import_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "IMPORTER UN AUDIO DU TÉLÉPHONE",
            color = Color.LightGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        Button(
            onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
            enabled = !loading && !exporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("import_audio_button"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f)),
            border = BorderStroke(1.5.dp, accent)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (loading) "DÉCODAGE EN COURS…" else if (fileName == null) "CHOISIR UN FICHIER AUDIO" else "CHANGER DE FICHIER",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (fileName != null) {
            Text(
                text = "${fileName}  •  ${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1
            )

            Slider(
                value = progress,
                onValueChange = { AudioFileBridge.seekTo(it) },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (playing) AudioFileBridge.pause() else AudioFileBridge.play() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("file_play_pause"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(
                        if (playing) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (playing) "PAUSE" else "LECTURE",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                OutlinedButton(
                    onClick = { AudioFileBridge.stop() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("file_stop"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("ARRÊT", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Lecture en boucle", color = Color.LightGray, fontSize = 10.sp)
                Switch(
                    checked = loop,
                    onCheckedChange = {
                        loop = it
                        AudioFileBridge.loopEnabled = it
                    }
                )
            }

            Text(
                text = "Le fichier passe par tous les effets du studio, à la place du micro. Utilisez un casque pour éviter l'écho.",
                color = Color.Gray,
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }

        // ---------- ENREGISTREMENT DE LA SORTIE (MICRO OU FICHIER) ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )

        Text(
            text = "ENREGISTRER LA SORTIE (MICRO OU FICHIER, AVEC LES EFFETS)",
            color = Color.LightGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        Button(
            onClick = {
                if (isRecording) processor.stopRecording() else processor.startRecording()
            },
            enabled = !exporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .testTag("record_button"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color(0xFFFF3B30) else accent.copy(alpha = 0.15f)
            ),
            border = BorderStroke(1.5.dp, if (isRecording) Color(0xFFFF3B30) else accent)
        ) {
            Icon(
                if (isRecording) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isRecording) Color.White else accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRecording) {
                    "ARRÊTER  •  ${recSeconds / 60}:${(recSeconds % 60).toString().padStart(2, '0')}"
                } else "DÉMARRER L'ENREGISTREMENT",
                color = if (isRecording) Color.White else accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )
        }

        if (hasRecording) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Enregistrement prêt : ${recSeconds / 60}:${(recSeconds % 60).toString().padStart(2, '0')}",
                    color = Color(0xFF39FF14),
                    fontSize = 10.sp
                )
                TextButton(onClick = { processor.clearRecording() }) {
                    Text("SUPPRIMER", color = Color(0xFFFF3B30), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Enregistre ce que vous entendez : votre voix au micro avec les effets, ou le fichier importé en lecture. Durée maximale : 5 minutes.",
            color = Color.Gray,
            fontSize = 9.sp,
            lineHeight = 12.sp
        )

        // ---------- EXPORT DU SON TRAITÉ ----------
        if (fileName != null || hasRecording) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            Text(
                text = "EXPORTER LE SON TRAITÉ",
                color = Color.LightGray,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioExporter.Format.values().forEach { f ->
                    val sel = format == f
                    Surface(
                        onClick = { format = f },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        color = if (sel) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (sel) accent else Color.Transparent)
                    ) {
                        Text(
                            text = f.label,
                            color = if (sel) accent else Color.White,
                            fontSize = 10.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            maxLines = 1
                        )
                    }
                }
            }

            if (format != AudioExporter.Format.WAV) {
                OptionChips(
                    label = "Qualité",
                    options = listOf(128, 192, 256),
                    selected = bitrate,
                    suffix = "kb/s",
                    onSelect = { bitrate = it }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Normaliser le volume (−1 dB)", color = Color.LightGray, fontSize = 10.sp)
                Switch(checked = normalize, onCheckedChange = { normalize = it })
            }

            OptionChips(
                label = "Fondu d'entrée",
                options = listOf(0, 1, 2, 3),
                selected = fadeIn,
                suffix = "s",
                onSelect = { fadeIn = it }
            )
            OptionChips(
                label = "Fondu de sortie",
                options = listOf(0, 1, 2, 3),
                selected = fadeOut,
                suffix = "s",
                onSelect = { fadeOut = it }
            )

            val busyLabel = "TRAITEMENT… ${(exportProgress * 100).toInt()} %"

            if (fileName != null) {
                Button(
                    onClick = {
                        AudioExporter.clearMessage()
                        scope.launch {
                            val ok = AudioExporter.render(processor, format, bitrate, context.cacheDir, normalize, fadeIn, fadeOut)
                            if (ok) {
                                val base = (fileName ?: "audio").substringBeforeLast('.')
                                val suggested = base + "_effets." + format.ext
                                launchSaver(format, suggested)
                            }
                        }
                    },
                    enabled = !exporting && !loading && !isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("export_audio_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (exporting) busyLabel else "EXPORTER LE FICHIER EN ${format.ext.uppercase()}",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (hasRecording) {
                Button(
                    onClick = {
                        AudioExporter.clearMessage()
                        scope.launch {
                            val ok = AudioExporter.renderRecording(processor, format, bitrate, context.cacheDir, normalize, fadeIn, fadeOut)
                            if (ok) {
                                val suggested = "enregistrement_effets." + format.ext
                                launchSaver(format, suggested)
                            }
                        }
                    },
                    enabled = !exporting && !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("export_recording_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (exporting) busyLabel else "EXPORTER L'ENREGISTREMENT EN ${format.ext.uppercase()}",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }

            if (exporting) {
                LinearProgressIndicator(progress = exportProgress, modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = "Le fichier importé reçoit les réglages actuels des effets, plus 3 secondes de queue pour la réverbération et l'écho. Vous choisissez ensuite le dossier d'enregistrement.",
                color = Color.Gray,
                fontSize = 9.sp,
                lineHeight = 12.sp
            )

            if (exportMessage != null) {
                Text(text = exportMessage!!, color = Color(0xFF39FF14), fontSize = 10.sp)
            }
        }

        if (message != null) {
            Text(text = message!!, color = Color(0xFFFFCC00), fontSize = 10.sp)
        }

        // Fond musical, écoute, partage et préréglages (fichier ExtrasPanel.kt)
        ExtrasPanel(viewModel)
    }
}
