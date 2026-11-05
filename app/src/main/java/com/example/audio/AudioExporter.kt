package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/** Destination des échantillons traités (stéréo entrelacé 16 bits). */
interface PcmSink {
    fun write(buf: ShortArray, count: Int)
    fun finish()
    fun abort()
}

/** Écrit un fichier WAV 16 bits stéréo (sans perte). */
class WavSink(file: File, totalFrames: Int, rate: Int) : PcmSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)

    init {
        val dataLen = totalFrames.toLong() * 4L
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt((36L + dataLen).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(2)
        h.putInt(rate)
        h.putInt(rate * 4)
        h.putShort(4)
        h.putShort(16)
        h.put("data".toByteArray())
        h.putInt(dataLen.toInt())
        out.write(h.array())
    }

    override fun write(buf: ShortArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(buf, 0, count)
        out.write(bb.array(), 0, count * 2)
    }

    override fun finish() {
        out.flush()
        out.close()
    }

    override fun abort() {
        try { out.close() } catch (_: Exception) { }
    }
}

/** Encode en AAC (conteneur M4A), lisible partout : téléphones, WhatsApp, ordinateurs. */
class AacSink(file: File, private val rate: Int, bitrate: Int) : PcmSink {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var framesIn = 0L
    private var eosSeen = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun drain(timeoutUs: Long) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (idx >= 0) {
                val ob = codec.getOutputBuffer(idx)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0
                }
                if (ob != null && info.size > 0 && muxerStarted) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, ob, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    eosSeen = true
                    return
                }
            }
        }
    }

    override fun write(buf: ShortArray, count: Int) {
        var off = 0
        while (off < count) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = codec.getInputBuffer(inIdx)!!
                ib.clear()
                ib.order(ByteOrder.nativeOrder())
                val cap = (ib.capacity() / 2) and 1.inv()
                val n = minOf(cap, count - off)
                ib.asShortBuffer().put(buf, off, n)
                codec.queueInputBuffer(inIdx, 0, n * 2, framesIn * 1_000_000L / rate, 0)
                framesIn += (n / 2).toLong()
                off += n
            }
            drain(0)
        }
    }

    override fun finish() {
        var queued = false
        while (!queued) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                codec.queueInputBuffer(
                    inIdx, 0, 0, framesIn * 1_000_000L / rate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                queued = true
            } else {
                drain(0)
            }
        }
        var guard = 0
        while (!eosSeen && guard < 2000) {
            drain(10_000)
            guard++
        }
        try { codec.stop() } catch (_: Exception) { }
        codec.release()
        if (muxerStarted) {
            try { muxer.stop() } catch (_: Exception) { }
        }
        muxer.release()
    }

    override fun abort() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }
}

/** Applique gain (normalisation) et fondus, puis transmet au destinataire final. Ne modifie jamais le tampon reçu. */
class ShapeSink(
    private val inner: PcmSink,
    private val totalFrames: Int,
    private val gain: Float,
    private val fadeInFrames: Int,
    private val fadeOutFrames: Int
) : PcmSink {
    private var frame = 0
    private var tmp = ShortArray(0)

    override fun write(buf: ShortArray, count: Int) {
        if (tmp.size < count) tmp = ShortArray(count)
        val n = count / 2
        for (i in 0 until n) {
            var g = gain
            val f = frame + i
            if (fadeInFrames > 0 && f < fadeInFrames) g *= f.toFloat() / fadeInFrames.toFloat()
            val rem = totalFrames - f
            if (fadeOutFrames > 0 && rem < fadeOutFrames) g *= rem.toFloat() / fadeOutFrames.toFloat()
            for (ch in 0..1) {
                val v = (buf[2 * i + ch] * g).toInt()
                tmp[2 * i + ch] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
            }
        }
        frame += n
        inner.write(tmp, count)
    }

    override fun finish() { inner.finish() }
    override fun abort() { inner.abort() }
}

/** Orchestre l'export : traite le fichier importé (ou l'enregistrement), puis l'enregistre. */
object AudioExporter {
    enum class Format(val label: String, val ext: String, val mime: String) {
        WAV("WAV (sans perte)", "wav", "audio/wav"),
        M4A("M4A / AAC (compressé)", "m4a", "audio/mp4")
    }

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var pendingFile: File? = null

    fun clearMessage() { _message.value = null }

    /** Fichier importé passé dans les effets actuels. Retourne true si prêt à être enregistré. */
    suspend fun render(
        processor: AudioProcessor,
        format: Format,
        bitrateKbps: Int,
        cacheDir: File,
        normalize: Boolean,
        fadeInSec: Int,
        fadeOutSec: Int
    ): Boolean = withContext(Dispatchers.IO) {
        renderFile(processor, format, bitrateKbps, cacheDir, normalize, fadeInSec, fadeOutSec)
    }

    /** Enregistrement de la sortie (déjà traité par les effets). */
    suspend fun renderRecording(
        processor: AudioProcessor,
        format: Format,
        bitrateKbps: Int,
        cacheDir: File,
        normalize: Boolean,
        fadeInSec: Int,
        fadeOutSec: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val frames = processor.recordFrames.value
        if (frames <= 0) {
            _message.value = "Aucun enregistrement à exporter."
            false
        } else {
            runExport(
                processor.sampleRate, frames, format, bitrateKbps, cacheDir, normalize, fadeInSec, fadeOutSec
            ) { sink, prog -> processor.readRecording(sink, prog) }
        }
    }

    private fun renderFile(
        processor: AudioProcessor,
        format: Format,
        bitrateKbps: Int,
        cacheDir: File,
        normalize: Boolean,
        fadeInSec: Int,
        fadeOutSec: Int
    ): Boolean {
        val src = AudioFileBridge.samples
        if (src == null) {
            _message.value = "Importez d'abord un fichier audio."
            return false
        }
        val rate = processor.sampleRate
        val tail = 3
        val totalFrames = src.size + tail * rate
        return runExport(
            rate, totalFrames, format, bitrateKbps, cacheDir, normalize, fadeInSec, fadeOutSec
        ) { sink, prog -> processor.renderOffline(src, tail, sink, prog) }
    }

    private fun runExport(
        rate: Int,
        totalFrames: Int,
        format: Format,
        bitrateKbps: Int,
        cacheDir: File,
        normalize: Boolean,
        fadeInSec: Int,
        fadeOutSec: Int,
        feed: (sink: (ShortArray, Int) -> Unit, onProgress: (Float) -> Unit) -> Unit
    ): Boolean {
        _exporting.value = true
        _progress.value = 0f
        _message.value = null
        val tmp = File(cacheDir, "export_tmp." + format.ext)
        var current: PcmSink? = null
        try {
            if (tmp.exists()) tmp.delete()

            // Passe 1 (optionnelle) : mesure du niveau crête pour la normalisation à -1 dB
            var gain = 1f
            var base = 0f
            var scale = 1f
            if (normalize) {
                var peak = 0
                feed(
                    { buf, n ->
                        for (i in 0 until n) {
                            val a = abs(buf[i].toInt())
                            if (a > peak) peak = a
                        }
                    },
                    { p -> _progress.value = p * 0.5f }
                )
                if (peak > 300) {
                    gain = min(32767f * 0.891f / peak.toFloat(), 8f)
                }
                base = 0.5f
                scale = 0.5f
            }

            val inner: PcmSink = if (format == Format.WAV) {
                WavSink(tmp, totalFrames, rate)
            } else {
                AacSink(tmp, rate, bitrateKbps * 1000)
            }
            val fi = min(fadeInSec * rate, totalFrames / 2)
            val fo = min(fadeOutSec * rate, totalFrames / 2)
            val sink = ShapeSink(inner, totalFrames, gain, fi, fo)
            current = sink

            // Passe 2 : rendu final avec gain et fondus
            feed(
                { buf, n -> sink.write(buf, n) },
                { p -> _progress.value = base + p * scale }
            )
            sink.finish()
            current = null
            pendingFile = tmp
            return true
        } catch (e: Exception) {
            try { current?.abort() } catch (_: Exception) { }
            _message.value = "Échec de l'export : ${e.message ?: "erreur inconnue"}"
            return false
        } finally {
            _exporting.value = false
        }
    }

    /** Copie le fichier temporaire vers l'emplacement choisi par l'utilisateur. */
    suspend fun saveTo(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) { copyOut(context, uri) }
    }

    private fun copyOut(context: Context, uri: Uri) {
        val f = pendingFile
        if (f == null) {
            _message.value = "Aucun fichier à enregistrer."
            return
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            _message.value = "Fichier enregistré avec succès."
        } catch (e: Exception) {
            _message.value = "Échec de l'enregistrement : ${e.message ?: "erreur inconnue"}"
        } finally {
            try { f.delete() } catch (_: Exception) { }
            pendingFile = null
        }
    }

    fun discard() {
        try { pendingFile?.delete() } catch (_: Exception) { }
        pendingFile = null
    }
}
