package com.example.ui.screens

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.MonitorControl
import com.example.audio.MusicBed
import com.example.audio.RecordingPlayer
import com.example.audio.ShareBridge
import com.example.viewmodel.VocalStudioViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.reflect.Modifier as JModifier

/**
 * Enregistre et rappelle l'ensemble des réglages d'effets, par réflexion sur l'état des effets.
 * Les préréglages sont stockés à part des réglages existants de l'application.
 */
private object PresetStore {
    private const val PREFS = "mahfoud_presets"

    fun names(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.sorted()

    fun get(ctx: Context, name: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null)

    fun put(ctx: Context, name: String, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, json).apply()
    }

    fun remove(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
    }

    private fun fieldsOf(c: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                if (!JModifier.isStatic(f.modifiers) && !f.isSynthetic) out.add(f)
            }
            k = k.superclass
        }
        return out
    }

    private fun cleanName(f: java.lang.reflect.Field): String = f.name.removeSuffix("\$delegate")

    private fun isModelObject(v: Any): Boolean =
        v.javaClass.name.startsWith("com.example.model") && v !is Enum<*>

    fun toJson(obj: Any, depth: Int = 0): JSONObject {
        val j = JSONObject()
        if (depth > 5) return j
        for (f in fieldsOf(obj.javaClass)) {
            try {
                f.isAccessible = true
                var v = f.get(obj)
                if (v is MutableState<*>) v = v.value
                val key = cleanName(f)
                if (v == null) continue
                if (v is Boolean) {
                    j.put(key, v)
                } else if (v is Number) {
                    j.put(key, v.toDouble())
                } else if (v is String) {
                    j.put(key, v)
                } else if (v is Enum<*>) {
                    j.put(key, v.name)
                } else if (isModelObject(v)) {
                    j.put(key, toJson(v, depth + 1))
                }
            } catch (_: Throwable) { }
        }
        return j
    }

    fun applyJson(obj: Any, j: JSONObject, depth: Int = 0) {
        if (depth > 5) return
        for (f in fieldsOf(obj.javaClass)) {
            try {
                f.isAccessible = true
                val key = cleanName(f)
                if (!j.has(key)) continue
                val raw = j.get(key)
                val cur = f.get(obj)
                if (cur is MutableState<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val ms = cur as MutableState<Any?>
                    val nv = convert(ms.value, raw)
                    if (nv != null) ms.value = nv
                    continue
                }
                if (cur == null) continue
                if (isModelObject(cur) && raw is JSONObject) {
                    applyJson(cur, raw, depth + 1)
                    continue
                }
                if (JModifier.isFinal(f.modifiers)) continue
                val nv = convert(cur, raw)
                if (nv != null) f.set(obj, nv)
            } catch (_: Throwable) { }
        }
    }

    private fun convert(cur: Any?, raw: Any): Any? {
        return when (cur) {
            is Boolean -> raw as? Boolean
            is Int -> (raw as? Number)?.toInt()
            is Long -> (raw as? Number)?.toLong()
            is Float -> (raw as? Number)?.toFloat()
            is Double -> (raw as? Number)?.toDouble()
            is String -> raw.toString()
            is Enum<*> -> cur.javaClass.enumConstants?.firstOrNull { (it as Enum<*>).name == raw.toString() }
            else -> null
        }
    }
}

@Composable
private fun VolumeChips(selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Volume musique", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(96.dp))
        listOf(25, 50, 75, 100).forEach { v ->
            val sel = selected == v
            Surface(
                onClick = { onSelect(v) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                color = if (sel) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (sel) accent else Color.Transparent)
            ) {
                Text(
                    text = "$v %",
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.LightGray,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun Divider1() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

@Composable
fun ExtrasPanel(viewModel: VocalStudioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = viewModel.audioProcessor
    val accent = MaterialTheme.colorScheme.primary

    // --- Retour d'écoute ---
    var muteMonitor by remember { mutableStateOf(MonitorControl.muteWhileRecording) }

    // --- Fond musical ---
    val musicName by MusicBed.fileName.collectAsState()
    val musicPlaying by MusicBed.playing.collectAsState()
    val musicLoading by MusicBed.loading.collectAsState()
    val musicMessage by MusicBed.message.collectAsState()
    var musicVolume by remember { mutableStateOf((MusicBed.volume * 100f).toInt()) }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { MusicBed.load(context, uri) }
        }
    }

    // --- Écoute de l'enregistrement + partage ---
    val isRecording by processor.isRecording.collectAsState()
    val recordFrames by processor.recordFrames.collectAsState()
    val hasRecording = recordFrames > 0 && !isRecording
    val listening by RecordingPlayer.playing.collectAsState()
    val sharedUri by ShareBridge.uri.collectAsState()

    // --- Préréglages ---
    var presetName by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(PresetStore.names(context)) }
    var presetInfo by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ===== RETOUR D'ÉCOUTE =====
        SectionTitle("ENREGISTREMENT SANS SIFFLEMENT")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Couper le retour d'écoute pendant l'enregistrement de la voix",
                color = Color.LightGray,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = muteMonitor,
                onCheckedChange = {
                    muteMonitor = it
                    MonitorControl.muteWhileRecording = it
                }
            )
        }

        Text(
            text = "Évite le sifflement (larsen) : l'enregistrement reste complet, mais vous ne vous entendez pas pendant la prise. Réécoutez-le ensuite avec le bouton « Écouter ». Désactivez cette option seulement avec un casque.",
            color = Color.Gray,
            fontSize = 9.sp,
            lineHeight = 12.sp
        )

        Divider1()

        // ===== FOND MUSICAL =====
        SectionTitle("FOND MUSICAL (MIXÉ AVEC VOTRE VOIX)")

        Button(
            onClick = { musicPicker.launch(arrayOf("audio/*")) },
            enabled = !musicLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f)),
            border = BorderStroke(1.5.dp, accent)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (musicLoading) "DÉCODAGE EN COURS…" else if (musicName == null) "CHOISIR UNE MUSIQUE" else "CHANGER DE MUSIQUE",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }

        if (musicLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (musicName != null) {
            Text(text = musicName ?: "", color = Color.White, fontSize = 11.sp, maxLines = 1)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (musicPlaying) MusicBed.pause() else MusicBed.play() },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(
                        if (musicPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (musicPlaying) "PAUSE" else "LECTURE",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                OutlinedButton(
                    onClick = { MusicBed.stop() },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("ARRÊT", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
                TextButton(onClick = { MusicBed.unload() }) {
                    Text("RETIRER", color = Color(0xFFFF3B30), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            VolumeChips(
                selected = musicVolume,
                onSelect = {
                    musicVolume = it
                    MusicBed.volume = it / 100f
                }
            )
        }

        if (musicMessage != null) {
            Text(text = musicMessage!!, color = Color(0xFFFFCC00), fontSize = 10.sp)
        }

        Text(
            text = "La musique joue en boucle, sans passer par les effets de la voix. Elle est incluse dans l'enregistrement. Si vous voulez l'entendre pendant la prise, utilisez un casque et désactivez la coupure du retour d'écoute.",
            color = Color.Gray,
            fontSize = 9.sp,
            lineHeight = 12.sp
        )

        // ===== ÉCOUTER L'ENREGISTREMENT / PARTAGER =====
        if (hasRecording || sharedUri != null) {
            Divider1()
            SectionTitle("ÉCOUTER ET PARTAGER")
        }

        if (hasRecording) {
            Button(
                onClick = { RecordingPlayer.toggle(processor) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Icon(
                    if (listening) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (listening) "ARRÊTER L'ÉCOUTE" else "ÉCOUTER L'ENREGISTREMENT",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        if (sharedUri != null) {
            Button(
                onClick = { ShareBridge.share(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f)),
                border = BorderStroke(1.5.dp, accent)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PARTAGER LE DERNIER EXPORT",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }
        }

        // ===== PRÉRÉGLAGES D'EFFETS =====
        Divider1()
        SectionTitle("PRÉRÉGLAGES D'EFFETS")

        OutlinedTextField(
            value = presetName,
            onValueChange = { presetName = it.take(30) },
            singleLine = true,
            label = { Text("Nom du préréglage", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val n = presetName.trim()
                if (n.isEmpty()) {
                    presetInfo = "Donnez d'abord un nom au préréglage."
                } else {
                    val json = PresetStore.toJson(processor.effectsState).toString()
                    PresetStore.put(context, n, json)
                    presets = PresetStore.names(context)
                    presetInfo = "Préréglage « $n » enregistré."
                    presetName = ""
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ENREGISTRER LES RÉGLAGES",
                color = Color.Black,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }

        presets.forEach { name ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val json = PresetStore.get(context, name)
                        if (json != null) {
                            try {
                                val j = JSONObject(json)
                                viewModel.updateEffects { state -> PresetStore.applyJson(state, j) }
                                presetInfo = "Préréglage « $name » appliqué."
                            } catch (e: Exception) {
                                presetInfo = "Impossible d'appliquer ce préréglage."
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("APPLIQUER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = {
                        PresetStore.remove(context, name)
                        presets = PresetStore.names(context)
                    }
                ) {
                    Text("SUPPR.", color = Color(0xFFFF3B30), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (presetInfo != null) {
            Text(text = presetInfo!!, color = Color(0xFF39FF14), fontSize = 10.sp)
        }

        Text(
            text = "Un préréglage mémorise tous les réglages des effets (égaliseur, réverbération, écho, etc.) pour les rappeler en un appui.",
            color = Color.Gray,
            fontSize = 9.sp,
            lineHeight = 12.sp
        )
    }
}
