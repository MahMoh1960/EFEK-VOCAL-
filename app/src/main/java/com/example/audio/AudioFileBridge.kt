package com.example.audio

import android.content.Context
import android.media.AudioFormat
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
 * Pont entre un fichier audio importé du téléphone et le moteur d'effets.
 * Le fichier est décodé en PCM 16 bits mono 44100 Hz, puis lu à la place du micro
 * dans la boucle de AudioProcessor : tous les effets s'appliquent donc dessus.
 */
object AudioFileBridge {
    private const val TARGET_RATE = 44100
    private const val MAX_SECONDS = 600 // 10 minutes maximum

    @Volatile var samples: ShortArray? = null
    @Volatile var position: Int = 0
    @Volatile var isPlaying: Boolean = false
    @Volatile var loopEnabled: Boolean = false

    private val _fileName = MutableStateFlow<String?>(null)
    val fileName: StateFlow<String?> = _fileName
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _durationSec = MutableStateFlow(0)
    val durationSec: StateFlow<Int> = _durationSec

    fun clearMessage() { _message.value = null }

    fun play() {
        val data = samples ?: return
        if (position >= data.size - 1) position = 0
        isPlaying = true
        _playing.value = true
    }

    fun pause() {
        isPlaying = false
        _playing.value = false
    }

    fun stop() {
        isPlaying = false
        position = 0
        _playing.value = false
        _progress.value = 0f
    }

    fun seekTo(fraction: Float) {
        val data = samples ?: return
        position = (fraction.coerceIn(0f, 1f) * (data.size - 1)).toInt()
        _progress.value = fraction.coerceIn(0f, 1f)
    }

    fun unload() {
        stop()
        samples = null
        _fileName.value = null
        _durationSec.value = 0
    }

    /** Appelé par la boucle audio (thread temps réel) : remplit le tampon d'entrée. */
    fun fillBuffer(out: ShortArray, count: Int) {
        val data = samples
        if (data == null || !isPlaying) {
            for (i in 0 until count) out[i] = 0
            return
        }
        var pos = position
        for (i in 0 until count) {
            if (pos >= data.size) {
                if (loopEnabled) {
                    pos = 0
                } else {
                    out[i] = 0
                    continue
                }
            }
            out[i] = data[pos]
            pos++
        }
        if (pos >= data.size && !loopEnabled) {
            position = data.size
            isPlaying = false
            _playing.value = false
            _progress.value = 1f
        } else {
            position = pos
            val p = pos.toFloat() / data.size.toFloat()
            if (kotlin.math.abs(p - _progress.value) > 0.002f) _progress.value = p
        }
    }

    private fun queryName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "audio"
    }

    /** Décode le fichier choisi. À appeler depuis une coroutine. */
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

            // Rééchantillonnage linéaire vers 44100 Hz
            val result: ShortArray
            if (rate == TARGET_RATE) {
                result = mono.copyOf(count)
            } else {
                val outLen = (count.toLong() * TARGET_RATE / rate).toInt()
                result = ShortArray(outLen)
                val ratio = rate.toDouble() / TARGET_RATE
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
            _durationSec.value = result.size / TARGET_RATE
            _fileName.value = queryName(context, uri)
            _progress.value = 0f
            if (truncated) _message.value = "Fichier tronqué aux 10 premières minutes."
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
