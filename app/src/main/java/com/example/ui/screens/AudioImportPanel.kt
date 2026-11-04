package com.example.ui.screens

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
import com.example.audio.AudioFileBridge
import kotlinx.coroutines.launch

@Composable
fun AudioImportPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val fileName by AudioFileBridge.fileName.collectAsState()
    val playing by AudioFileBridge.playing.collectAsState()
    val progress by AudioFileBridge.progress.collectAsState()
    val loading by AudioFileBridge.loading.collectAsState()
    val message by AudioFileBridge.message.collectAsState()
    val duration by AudioFileBridge.durationSec.collectAsState()
    var loop by remember { mutableStateOf(AudioFileBridge.loopEnabled) }

    val accent = MaterialTheme.colorScheme.primary

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { AudioFileBridge.load(context, uri) }
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
            enabled = !loading,
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

        if (message != null) {
            Text(text = message!!, color = Color(0xFFFFCC00), fontSize = 10.sp)
        }
    }
}
