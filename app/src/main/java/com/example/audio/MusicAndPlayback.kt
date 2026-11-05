package com.example.audio

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Fond musical : un fichier audio mélangé à la sortie (après les effets), en boucle.
 * Il est donc inclus dans l'enregistrement. Il ne passe pas par les effets de la voix.
 */
object MusicBed {
    private const val RATE = 44100
    private const val MAX_SECONDS = 600

    @Volatile var samples: ShortArray? = null
    @Volatile var position: Int = 0
    @Volatile var isPlaying: Boolean = false
    @Volatile var volume: Float = 0.5f

    private val _fileName = MutableStateFlow<String?>(null)
    val fileName: StateFlow<String?> = _fileName
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun play() {
        if (samples == null) return
        isPlaying = true
        _playing.value = true
    }

    fun pause() {
        isPlaying = false
        _playing.value = false
    }

    fun stop() {
        pause()
        position = 0
    }

    fun unload() {
        stop()
        samples = null
        _fileName.value = null
    }

    /** Appelé par la boucle audio : ajoute la musique (mono) aux deux canaux du tampon stéréo entrelacé. */
    fun mixInto(out: ShortArray, frames: Int) {
        val data = samples ?: return
        if (!isPlaying) return
        val v = volume
        var pos = position
        for (i in 0 until frames) {
            if (pos >= data.size) pos = 0
            val s = (data[pos] * v).toInt()
            pos++
            val l = out[2 * i] + s
            val r = out[2 * i + 1] + s
            out[2 * i] = (if (l > 32767) 32767 else if (l < -32768) -32768 else l).toShort()
            out[2 * i + 1] = (if (r > 32767) 32767 else if (r < -32768) -32768 else r).toShort()
        }
        position = pos
    }

    private fun queryName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "musique"
    }

    suspend fun load(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) { decode(context, uri) }
    }

    private fun decode(context: Context, uri: Uri) {
        _loading.value = true
        _message.value = null
        pause()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                _message.value = "Ce fichier ne contient pas d'audio lisible."
                return
            }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var mono = ShortArray(rate * 30)
            var count = 0
            var maxFrames = rate * MAX_SECONDS
            var truncated = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    maxFrames = rate * MAX_SECONDS
                } else if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    val ch = if (channels < 1) 1 else channels
                    if (floatPcm) {
                        val fb = ob.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until ch) sum += fb.get()
                            val v = (sum / ch * 32767f).toInt().coerceIn(-32768, 32767)
                            if (count >= maxFrames) { truncated = true; break }
                            if (count >= mono.size) mono = mono.copyOf(mono.size * 2)
                            mono[count++] = v.toShort()
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (f in 0 until frames) {
                            var sum = 0
                            for (c in 0 until ch) sum += sb.get().toInt()
                            if (count >= maxFrames) { truncated = true; break }
                            if (count >= mono.size) mono = mono.copyOf(mono.size * 2)
                            mono[count++] = (sum / ch).toShort()
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || truncated) outputDone = true
                }
            }

            if (count < 1024) {
                _message.value = "Fichier audio vide ou illisible."
                return
            }

            val result: ShortArray
            if (rate == RATE) {
                result = mono.copyOf(count)
            } else {
                val outLen = (count.toLong() * RATE / rate).toInt()
                result = ShortArray(outLen)
                val ratio = rate.toDouble() / RATE
                for (i in 0 until outLen) {
                    val src = i * ratio
                    val i0 = src.toInt()
                    val i1 = if (i0 + 1 < count) i0 + 1 else i0
                    val frac = (src - i0).toFloat()
                    result[i] = (mono[i0] * (1f - frac) + mono[i1] * frac).toInt().toShort()
                }
            }

            position = 0
            samples = result
            _fileName.value = queryName(context, uri)
        } catch (e: Exception) {
            _message.value = "Impossible de lire ce fichier : ${e.message ?: "format non pris en charge"}"
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
            _loading.value = false
        }
    }
}

/** Écoute de l'enregistrement dans l'application, avant l'export. */
object RecordingPlayer {
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    @Volatile private var stopFlag = false

    private class StopPlayback : RuntimeException()

    fun toggle(processor: AudioProcessor) {
        if (_playing.value) stop() else start(processor)
    }

    fun stop() {
        stopFlag = true
    }

    private fun start(processor: AudioProcessor) {
        if (_playing.value) return
        stopFlag = false
        _playing.value = true
        Thread {
            var track: AudioTrack? = null
            try {
                val rate = processor.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufBytes = if (minBuf > 16384) minBuf else 16384
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                t.play()
                processor.readRecording(
                    { buf, n ->
                        var off = 0
                        while (off < n) {
                            if (stopFlag) throw StopPlayback()
                            val w = t.write(buf, off, n - off)
                            if (w < 0) throw StopPlayback()
                            off += w
                        }
                    },
                    { }
                )
                // Laisse le temps de jouer la fin du tampon
                var waitMs = (bufBytes / 4) * 1000L / rate + 150L
                while (waitMs > 0 && !stopFlag) {
                    Thread.sleep(50)
                    waitMs -= 50
                }
            } catch (_: StopPlayback) {
            } catch (_: Throwable) {
            } finally {
                try { track?.stop() } catch (_: Throwable) { }
                try { track?.release() } catch (_: Throwable) { }
                _playing.value = false
            }
        }.start()
    }
}

/** Mémorise le dernier fichier exporté et permet de le partager (WhatsApp, e-mail…). */
object ShareBridge {
    @Volatile var pendingMime: String = "audio/*"
    @Volatile private var mime: String = "audio/*"

    private val _uri = MutableStateFlow<Uri?>(null)
    val uri: StateFlow<Uri?> = _uri

    fun onSaved(saved: Uri) {
        mime = pendingMime
        _uri.value = saved
    }

    fun share(context: Context) {
        val u = _uri.value ?: return
        try {
            val send = Intent(Intent.ACTION_SEND)
            send.type = mime
            send.putExtra(Intent.EXTRA_STREAM, u)
            send.clipData = ClipData.newRawUri("audio", u)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, "Partager le fichier audio")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) { }
    }
}
