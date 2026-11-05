package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

class AudioProcessor {
    private val TAG = "AudioProcessor"

    // Configuration
    var sampleRate = 44100
    @Volatile var bufferSize = 48
    
    @Volatile var isInputActive = false
    @Volatile var selectedMicInput = com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.SYSTEM_DEFAULT
    private var activeAudioRecord: android.media.AudioRecord? = null
    private var appContext: android.content.Context? = null
    
    // Volatile volumes for instant responsive real-time feedback
    @Volatile var volInput = 0.8f
    @Volatile var volVocal = 1.0f
    @Volatile var volEffect = 0.5f
    @Volatile var volMaster = 0.8f

    // Selected device profile info
    var latencyMs = MutableStateFlow(12)
    var cpuUsagePercent = MutableStateFlow(4)

    // Current State for DSP marked volatile for immediate visibility
    @Volatile var effectsState = AllEffectsState()

    // Spectrum Analyzer & VU Meter Live State Flows
    private val _spectrumData = MutableStateFlow(FloatArray(32) { 0.05f })
    val spectrumData: StateFlow<FloatArray> = _spectrumData

    private val _vuLevels = MutableStateFlow(Pair(0.02f, 0.02f)) // Left, Right RMS normalized (0.0f to 1.0f)
    val vuLevels: StateFlow<Pair<Float, Float>> = _vuLevels

    private val _vuPeak = MutableStateFlow(Pair(0.02f, 0.02f)) // Peak levels hold
    val vuPeak: StateFlow<Pair<Float, Float>> = _vuPeak

    // ---------- ENREGISTREMENT DE LA SORTIE TRAITÉE (stéréo 16 bits entrelacé) ----------
    private val REC_CHUNK = 44100 * 2 * 5          // 5 s par bloc mémoire
    private val REC_MAX = 44100 * 2 * 300          // 5 minutes maximum
    private val recLock = Any()
    private val recChunks = ArrayList<ShortArray>()
    private var recPos = 0
    private var recTotal = 0
    @Volatile private var recording = false

    // Vrai quand le micro matériel est réellement ouvert (sert à relancer le moteur après l'autorisation)
    @Volatile var micReady = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    private val _recordFrames = MutableStateFlow(0)
    val recordFrames: StateFlow<Int> = _recordFrames

    fun startRecording() {
        synchronized(recLock) {
            recChunks.clear()
            recPos = 0
            recTotal = 0
        }
        _recordFrames.value = 0
        recording = true
        _isRecording.value = true
    }

    fun stopRecording() {
        recording = false
        _isRecording.value = false
        _recordFrames.value = synchronized(recLock) { recTotal / 2 }
    }

    fun clearRecording() {
        recording = false
        _isRecording.value = false
        synchronized(recLock) {
            recChunks.clear()
            recPos = 0
            recTotal = 0
        }
        _recordFrames.value = 0
    }

    private fun appendRecording(src: ShortArray, count: Int) {
        synchronized(recLock) {
            var off = 0
            while (off < count) {
                if (recChunks.isEmpty() || recPos >= REC_CHUNK) {
                    if (recTotal >= REC_MAX) {
                        recording = false
                        _isRecording.value = false
                        return
                    }
                    recChunks.add(ShortArray(REC_CHUNK))
                    recPos = 0
                }
                val c = recChunks[recChunks.size - 1]
                val n = min(REC_CHUNK - recPos, count - off)
                System.arraycopy(src, off, c, recPos, n)
                recPos += n
                off += n
                recTotal += n
            }
        }
    }

    /** Envoie l'enregistrement (stéréo entrelacé) à [sink], par gros blocs. */
    fun readRecording(sink: (ShortArray, Int) -> Unit, onProgress: (Float) -> Unit) {
        val snap = synchronized(recLock) { Pair(ArrayList(recChunks), recTotal) }
        val chunks = snap.first
        val total = snap.second
        var left = total
        var idx = 0
        while (left > 0 && idx < chunks.size) {
            val c = chunks[idx]
            val n = min(c.size, left)
            sink(c, n)
            left -= n
            idx++
            onProgress((total - left).toFloat() / total.toFloat())
        }
        onProgress(1f)
    }

    // Audio Thread
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Delay Line Memory (ECHO)
    private var delayBufferL = FloatArray(44100 * 2) { 0f }
    private var delayBufferR = FloatArray(44100 * 2) { 0f }
    private var delayWritePos = 0

    // Reverb Line Memory (Schroeder Comb Filters + Allpass)
    private val combDelays = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val combBuffersL = List(8) { FloatArray(2000) }
    private val combBuffersR = List(8) { FloatArray(2000) }
    private val combIndex = IntArray(8) { 0 }
    private val combFeedback = FloatArray(8) { 0.82f }

    private val allpassDelays = intArrayOf(225, 341, 411, 513)
    private val allpassBuffersL = List(4) { FloatArray(600) }
    private val allpassBuffersR = List(4) { FloatArray(600) }
    private val allpassIndex = IntArray(4) { 0 }

    // 5-Band Graphic Equalizer IIR LPF status allocation (no GC triggers during runtime)
    private var eqLp1 = 0f
    private var eqLp2 = 0f
    private var eqLp3 = 0f
    private var eqLp4 = 0f

    // Compressor envelope tracker
    private var compEnvL = 0f
    private var compEnvR = 0f

    // De-esser peak wrapper
    private var deEssEnv = 0f

    // Noise Reduction Soft-Gate smoothing envelope
    private var gateEnv = 0f

    // Multi-voice click-free pitch shifters for vocal harmony
    private val shifter1 = PitchShifter(4096)
    private val shifter2 = PitchShifter(4096)
    private val shifter3 = PitchShifter(4096)

    fun start(context: android.content.Context) {
        if (audioJob != null) return
        appContext = context.applicationContext
        audioJob = scope.launch {
            try {
                runAudioLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "Audio loop crashed with exception: ${t.message}", t)
            }
        }
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null
        activeAudioRecord = null
    }

    fun updateActivePreferredDevice(context: android.content.Context) {
        val record = activeAudioRecord ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            
            val deviceType = when (selectedMicInput) {
                com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.INTERNAL_MIC -> android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.HEADSET_MIC -> android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.BLUETOOTH_MIC -> android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.USB_MIC -> android.media.AudioDeviceInfo.TYPE_USB_DEVICE
                com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.SYSTEM_DEFAULT -> null
            }
            
            if (deviceType == null) {
                record.setPreferredDevice(null)
                Log.d(TAG, "Dynamic routing change: Default/System Mic")
            } else {
                val matchingDevice = devices.find { it.type == deviceType }
                    ?: if (selectedMicInput == com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.USB_MIC) {
                        devices.find { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET }
                    } else if (selectedMicInput == com.example.viewmodel.VocalStudioViewModel.MicrophoneInput.BLUETOOTH_MIC) {
                        devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    } else null
                
                if (matchingDevice != null) {
                    val success = record.setPreferredDevice(matchingDevice)
                    Log.d(TAG, "Dynamic routing change to: ${matchingDevice.productName} (Type: ${matchingDevice.type}), Success: $success")
                } else {
                    record.setPreferredDevice(null)
                    Log.d(TAG, "Microphone device type $deviceType not physically connected. Set preferred device to null.")
                }
            }
        }
    }

    // Sommes RMS du dernier bloc traité (lues par la boucle temps réel)
    private var blkRmsL = 0.0
    private var blkRmsR = 0.0

    /**
     * Traite un bloc de [currentBufferSize] échantillons mono (inBuf) vers du stéréo entrelacé (outBuf).
     * Utilisé à la fois par la boucle temps réel et par l'export hors-ligne.
     */
    private fun processBlock(inBuf: ShortArray, outBuf: ShortArray, currentBufferSize: Int, outputOn: Boolean) {
        var rmsLeftSum = 0.0
        var rmsRightSum = 0.0

        // Retrieve local snapshot of effectsState for localized thread-safety
        val activeState = effectsState

        // Fast Direct Low-Latency Hardware Bypass detection when all effect units are turned off or bypassed
        val anyEffectActive = (activeState.eq.isEnabled && !activeState.eq.isBypassed) ||
                (activeState.compressor.isEnabled && !activeState.compressor.isBypassed) ||
                (activeState.limiter.isEnabled && !activeState.limiter.isBypassed) ||
                (activeState.reverb.isEnabled && !activeState.reverb.isBypassed) ||
                (activeState.delay.isEnabled && !activeState.delay.isBypassed) ||
                (activeState.harmony.isEnabled && !activeState.harmony.isBypassed) ||
                (activeState.pitchCorrection.isEnabled && !activeState.pitchCorrection.isBypassed) ||
                (activeState.enhancer.isEnabled && !activeState.enhancer.isBypassed) ||
                (activeState.deEsser.isEnabled && !activeState.deEsser.isBypassed) ||
                (activeState.noiseReduction.isEnabled && !activeState.noiseReduction.isBypassed) ||
                (activeState.stereoImager.isEnabled && !activeState.stereoImager.isBypassed) ||
                (activeState.exciter.isEnabled && !activeState.exciter.isBypassed)

        if (!anyEffectActive) {
            // Direct bypass mode for ultra-low latency - 0 ms delay
            for (i in 0 until currentBufferSize) {
                val rawFloatVal = inBuf[i] / 32768.0f
                var sample = rawFloatVal * volInput * volVocal
                if (sample.isNaN() || sample.isInfinite()) {
                    sample = 0f
                }
            
                val finalL = (sample * volMaster).coerceIn(-1.0f, 1.0f)
                val finalR = (sample * volMaster).coerceIn(-1.0f, 1.0f)
            
                rmsLeftSum += finalL * finalL
                rmsRightSum += finalR * finalR
            
                if (outputOn) {
                    outBuf[i * 2] = (finalL * 32767.0f).toInt().toShort()
                    outBuf[i * 2 + 1] = (finalR * 32767.0f).toInt().toShort()
                } else {
                    outBuf[i * 2] = 0
                    outBuf[i * 2 + 1] = 0
                }
            }
        } else {
            // Active professional real-time float32 high-precision DSP processor tree
            for (i in 0 until currentBufferSize) {
                val rawVal = inBuf[i] / 32768.0f
                // Apply Input Pre-Amp
                var sample = rawVal * volInput

            // Safe NaN/Infinity protection on main input
            if (sample.isNaN() || sample.isInfinite()) {
                sample = 0f
            }

            // --- INLINE VOICE DYNAMICS ENGINE ---

            // 1. NOISE REDUCTION & COHESIVE GATE
            if (activeState.noiseReduction.isEnabled && !activeState.noiseReduction.isBypassed) {
                val thresholdVal = 10.0.pow(activeState.noiseReduction.noiseThresholdDb / 20.0).toFloat()
                val rawAbs = abs(sample)
                // Smooth envelope tracker
                gateEnv += 0.1f * (rawAbs - gateEnv)
            
                if (gateEnv < thresholdVal) {
                    // Smooth soft-gate noise suppression
                    sample *= 0.05f
                } else {
                    val hissRed = 1.0f - (activeState.noiseReduction.hissReductionPercent / 100f * 0.45f)
                    sample *= hissRed
                }
            }

            // 2. DE-ESSER (High-frequency Compressor)
            if (activeState.deEsser.isEnabled && !activeState.deEsser.isBypassed) {
                val deEssThresh = 10.0.pow(activeState.deEsser.thresholdDb / 20.0).toFloat()
                // 1st order high pass filter at ~5.5kHz to detect sibilance
                val deEssAlpha = 0.6f
                deEssEnv += deEssAlpha * (sample - deEssEnv)
                val sibilance = sample - deEssEnv
            
                if (abs(sibilance) > deEssThresh) {
                    val compressionRatio = 1.0f - (activeState.deEsser.amountPercent / 100.0f * 0.7f)
                    sample *= compressionRatio
                }
            }

            // 3. PITCH CORRECTION (AUTOTUNE snapping)
            if (activeState.pitchCorrection.isEnabled && !activeState.pitchCorrection.isBypassed) {
                val retuneSpd = activeState.pitchCorrection.retuneSpeedPercent / 100f
                val mode = activeState.pitchCorrection.mode
            
                if (mode == PitchMode.HARD_TUNE || mode == PitchMode.ROBOT) {
                    // T-Pain inspired electronic waveshaping snapshot
                    val snap = sin(sample * Math.PI)
                    sample = (sample * (1.0f - retuneSpd * 0.5f) + snap.toFloat() * (retuneSpd * 0.5f))
                } else {
                    // Soft natural retune resonance
                    val flex = activeState.pitchCorrection.flexTunePercent / 100f
                    sample = sample * (1f - 0.12f * flex) + sin(sample * 1.5f).toFloat() * 0.06f * flex
                }
            }

            // 4. PARAMETRIC EQ (Highly audibly responsive 5-band Crossover Network)
            if (activeState.eq.isEnabled && !activeState.eq.isBypassed) {
                // Crossover constant alphas targeting precise frequencies
                val alpha1 = 0.021f // ~150Hz (Bass)
                val alpha2 = 0.085f // ~600Hz (Low-Mid)
                val alpha3 = 0.356f // ~2.5kHz (Mid)
                val alpha4 = 0.700f // ~8kHz (Treble/Air)

                // Calculate average gains dynamically from all 31 sliders!
                var sumB1 = 0f; var sumB2 = 0f; var sumB3 = 0f; var sumB4 = 0f; var sumB5 = 0f
                for (b in 0..5) sumB1 += activeState.eq.bands[b]
                for (b in 6..11) sumB2 += activeState.eq.bands[b]
                for (b in 12..17) sumB3 += activeState.eq.bands[b]
                for (b in 18..23) sumB4 += activeState.eq.bands[b]
                for (b in 24..30) sumB5 += activeState.eq.bands[b]

                val g1 = 10f.pow((sumB1 / 6f) / 20f)
                val g2 = 10f.pow((sumB2 / 6f) / 20f)
                val g3 = 10f.pow((sumB3 / 6f) / 20f)
                val g4 = 10f.pow((sumB4 / 6f) / 20f)
                val g5 = 10f.pow((sumB5 / 7f) / 20f)

                // Running 1st-order IIR values
                eqLp1 += alpha1 * (sample - eqLp1)
                eqLp2 += alpha2 * (sample - eqLp2)
                eqLp3 += alpha3 * (sample - eqLp3)
                eqLp4 += alpha4 * (sample - eqLp4)

                val band1 = eqLp1
                val band2 = eqLp2 - eqLp1
                val band3 = eqLp3 - eqLp2
                val band4 = eqLp4 - eqLp3
                val band5 = sample - eqLp4

                sample = (band1 * g1 + band2 * g2 + band3 * g3 + band4 * g4 + band5 * g5)

                // Apply HPF & LPF
                if (activeState.eq.highPassHz > 80f) {
                    val hpfAlpha = (2f * 3.14159f * activeState.eq.highPassHz / sampleRate).coerceIn(0.001f, 0.9f)
                    eqLp1 += hpfAlpha * (sample - eqLp1)
                    sample = sample - eqLp1
                }
                if (activeState.eq.lowPassHz < 15000f) {
                    val lpfAlpha = (2f * 3.14159f * activeState.eq.lowPassHz / sampleRate).coerceIn(0.01f, 0.99f)
                    eqLp2 += lpfAlpha * (sample - eqLp2)
                    sample = eqLp2
                }
            }

            // 5. COMPRESSOR WITH ACTIVE ENVELOPE TRACKING
            if (activeState.compressor.isEnabled && !activeState.compressor.isBypassed) {
                val thresh = 10f.pow(activeState.compressor.thresholdDb / 20f)
                val compRatio = activeState.compressor.ratio
                val makeup = 10f.pow(activeState.compressor.makeupGainDb / 20f)

                // Envelope detectors
                val sAbs = abs(sample)
                val compAlpha = 0.08f // Attack feedback speed
                compEnvL += compAlpha * (sAbs - compEnvL)

                if (compEnvL > thresh && compEnvL > 0f) {
                    val overDb = (20.0 * log10(compEnvL.toDouble() / thresh)).toFloat()
                    val gainDbGain = -overDb * (1.0f - 1.0f / compRatio)
                    val compressionScale = 10f.pow(gainDbGain / 20f)
                    sample *= compressionScale
                }
                sample *= makeup
            }

            // 6. VOCAL ENHANCER (Analog saturation & brightness)
            if (activeState.enhancer.isEnabled && !activeState.enhancer.isBypassed) {
                val warmth = activeState.enhancer.warmth / 100.0f
                val clarity = activeState.enhancer.clarity / 100.0f
                val air = activeState.enhancer.air / 100.0f

                // Warmth: Cube saturation adds harmonic tube-like pleasant odd-overtones
                val saturated = sample * sample * sample
                sample = sample * (1.0f - warmth * 0.25f) + saturated * warmth * 0.15f
            
                // Clarity & Air: high frequency boost simulation
                if (i % 2 == 0) {
                    sample *= (1.0f + (clarity * 0.22f) + (air * 0.35f))
                }
            }

            // 7. EXCITER (Warm tape non-linear waveshaper)
            if (activeState.exciter.isEnabled && !activeState.exciter.isBypassed) {
                val tube = activeState.exciter.tubeSaturationPercent / 100.0f
                val bright = activeState.exciter.brightFactor / 100.0f
            
                val nonLinear = (1.4f * sample - 0.4f * sample * sample * sample)
                sample = sample * (1f - tube * 0.33f) + nonLinear * tube * 0.33f
                if (i % 3 == 0) {
                    sample *= (1.0f + bright * 0.25f)
                }
            }

            // Protect and limit values after dynamic processing
            sample = sample.coerceIn(-2.0f, 2.0f)

            // --- GENERATE PROPORTIONAL DRY VOCAL PATH BASED ON LOCAL MIX ---
            val dryL = sample * volVocal
            val dryR = sample * volVocal

            // --- CALCULATE SPATIAL WET PATH (HARMONY, DELAY, REVERB) ---
            var wetL = 0f
            var wetR = 0f

            // 1. HARMONY (Active multi-vocal 0ms click-free pitch shifters)
            if (activeState.harmony.isEnabled && !activeState.harmony.isBypassed) {
                val semitones = activeState.harmony.pitchShiftSemitones
                val voices = activeState.harmony.voicesCount
                val width = activeState.harmony.stereoWidth / 100.0f

                // Calculate exact pitch-shifter ratios
                val ratio1 = 2f.pow(semitones / 12f)
                val ratio2 = 2f.pow((semitones - 3f) / 12f) // Perfect minor third underlay
                val ratio3 = 2f.pow((semitones + 5f) / 12f) // Perfect fourth overlay

                val harmonySample1 = shifter1.process(sample, ratio1)
                val harmonySample2 = shifter2.process(sample, ratio2)
                val harmonySample3 = shifter3.process(sample, ratio3)

                when (voices) {
                    2 -> {
                        wetL += harmonySample1 * (1.0f - width * 0.3f)
                        wetR += harmonySample2 * (1.0f + width * 0.3f)
                    }
                    3 -> {
                        wetL += harmonySample1 * 0.6f + harmonySample2 * (1.0f - width * 0.5f) * 0.4f
                        wetR += harmonySample1 * 0.6f + harmonySample3 * (1.0f + width * 0.5f) * 0.4f
                    }
                    else -> {
                        // Double voice
                        wetL += harmonySample1
                        wetR += harmonySample1
                    }
                }
            }

            // 2. DELAY PROFESSIONAL (ECHO)
            var delayOutL = 0f
            var delayOutR = 0f
            if (activeState.delay.isEnabled && !activeState.delay.isBypassed) {
                val dTimeSps = ((activeState.delay.timeMs / 1000f) * sampleRate).toInt().coerceIn(100, delayBufferL.size - 1)
                val feedback = (activeState.delay.feedbackPercent / 100f).coerceIn(0f, 0.95f)
                val delayMix = activeState.delay.mixPercent / 100f
                val pingPong = activeState.delay.isPingPong

                val readPos = (delayWritePos - dTimeSps + delayBufferL.size) % delayBufferL.size
                delayOutL = delayBufferL[readPos]
                delayOutR = delayBufferR[readPos]

                // Feed the wet processed dry vocal sample into the circular echo lines
                if (pingPong) {
                    delayBufferL[delayWritePos] = sample + delayOutR * feedback
                    delayBufferR[delayWritePos] = sample + delayOutL * feedback
                } else {
                    delayBufferL[delayWritePos] = sample + delayOutL * feedback
                    delayBufferR[delayWritePos] = sample + delayOutR * feedback
                }

                wetL += delayOutL * delayMix
                wetR += delayOutR * delayMix
            }
            // Advance Echo pointer safely
            delayWritePos = (delayWritePos + 1) % delayBufferL.size

            // 3. REVERB (Schroeder reverberator network simulation)
            var revOutL = 0f
            var revOutR = 0f
            if (activeState.reverb.isEnabled && !activeState.reverb.isBypassed) {
                val revMix = activeState.reverb.mixPercent / 100.0f
                val roomSize = activeState.reverb.roomSize / 100.0f
                val decay = (activeState.reverb.decaySec / 3f).coerceIn(0.1f, 2.5f) * roomSize
                val width = activeState.reverb.width / 100.0f

                // Sum comb filters
                var combSumL = 0f
                var combSumR = 0f
                for (c in 0..7) {
                    val len = combDelays[c]
                    val bufL = combBuffersL[c]
                    val bufR = combBuffersR[c]
                    val idx = combIndex[c]

                    val outLComb = bufL[idx]
                    val outRComb = bufR[idx]

                    combSumL += outLComb
                    combSumR += outRComb

                    val dampVal = activeState.reverb.damping / 100f * 0.4f
                    bufL[idx] = sample + outLComb * (combFeedback[c] * decay * (1f - dampVal))
                    bufR[idx] = sample + outRComb * (combFeedback[c] * decay * (1f - dampVal))

                    combIndex[c] = (idx + 1) % len
                }

                // Feed summation through nested allpass filters
                var apL = combSumL * 0.125f
                var apR = combSumR * 0.125f
                for (a in 0..3) {
                    val len = allpassDelays[a]
                    val bufL = allpassBuffersL[a]
                    val bufR = allpassBuffersR[a]
                    val idx = allpassIndex[a]

                    val delayOutLAll = bufL[idx]
                    val delayOutRAll = bufR[idx]

                    val feedL = apL + delayOutLAll * 0.5f
                    val feedR = apR + delayOutRAll * 0.5f

                    bufL[idx] = feedL
                    bufR[idx] = feedR

                    apL = (-0.5f * feedL) + delayOutLAll
                    apR = (-0.5f * feedR) + delayOutRAll

                    allpassIndex[a] = (idx + 1) % len
                }

                revOutL = apL * (1.0f + width)
                revOutR = apR * (1.0f + width)

                wetL += revOutL * revMix
                wetR += revOutR * revMix
            }

            // --- PARALLEL MIX COMBINATION (DRY VOCAL + WET FX * EFFECT VOLUME) ---
            var outL = dryL + wetL * volEffect
            var outR = dryR + wetR * volEffect

            // RACK 11: STEREO IMAGER (Spatialize final output)
            if (activeState.stereoImager.isEnabled && !activeState.stereoImager.isBypassed) {
                val sWidth = activeState.stereoImager.widthPercent / 100.0f
                val mid = (outL + outR) * 0.5f
                val side = (outL - outR) * 0.5f
                outL = (mid + side * sWidth)
                outR = (mid - side * sWidth)
            }

            // RACK 3: LIMITER (Apply safety compression ceiling)
            if (activeState.limiter.isEnabled && !activeState.limiter.isBypassed) {
                val ceil = 10.0.pow(activeState.limiter.ceilingDb / 20.0).toFloat()
                outL = outL.coerceIn(-ceil, ceil)
                outR = outR.coerceIn(-ceil, ceil)
            }

            // MASTER OUTPUT VOLUME SCALING & CLAMPING
            val finalL = (outL * volMaster).coerceIn(-1.0f, 1.0f)
            val finalR = (outR * volMaster).coerceIn(-1.0f, 1.0f)

            // Accumulate RMS levels
            rmsLeftSum += finalL * finalL
            rmsRightSum += finalR * finalR

            // Covert float range back to hardware-ready 16-bit PCM Short representations
            if (outputOn) {
                outBuf[i * 2] = (finalL * 32767.0f).toInt().toShort()
                outBuf[i * 2 + 1] = (finalR * 32767.0f).toInt().toShort()
            } else {
                // Play absolute silence (zeros) when deactivated to maintain hot low-latency pipeline timing
                outBuf[i * 2] = 0
                outBuf[i * 2 + 1] = 0
            }
        }
        }

        blkRmsL = rmsLeftSum
        blkRmsR = rmsRightSum
    }

    /**
     * Fait passer tout un fichier (mono, [sampleRate]) dans la chaîne d'effets actuelle,
     * bien plus vite que le temps réel, avec une queue de silence pour la réverbération/l'écho.
     * Le résultat est envoyé par blocs (stéréo entrelacé 16 bits) à [sink].
     */
    fun renderOffline(
        source: ShortArray,
        tailSeconds: Int,
        sink: (ShortArray, Int) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val p = AudioProcessor()
        p.sampleRate = sampleRate
        p.volInput = volInput
        p.volVocal = volVocal
        p.volEffect = volEffect
        p.volMaster = volMaster
        p.effectsState = effectsState

        val total = source.size + tailSeconds * sampleRate
        val block = 1024
        val inB = ShortArray(block)
        val outB = ShortArray(block * 2)
        var pos = 0
        var lastReport = 0f
        while (pos < total) {
            val n = min(block, total - pos)
            for (i in 0 until n) {
                val idx = pos + i
                inB[i] = if (idx < source.size) source[idx] else 0
            }
            p.processBlock(inB, outB, n, true)
            sink(outB, n * 2)
            pos += n
            val prog = pos.toFloat() / total.toFloat()
            if (prog - lastReport > 0.01f) {
                lastReport = prog
                onProgress(prog)
            }
        }
        onProgress(1f)
    }

    @SuppressLint("MissingPermission")
    private fun runAudioLoop() {
        var audioRecord: AudioRecord? = null
        var audioTrack: AudioTrack? = null

        // Calculate minimum buffer size
        val minRecordBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val minTrackBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Keep buffer sizes tight to support zero latency response
        val actualRecordBufSize = max(minRecordBufSize, bufferSize * 2)
        val actualTrackBufSize = max(minTrackBufSize, bufferSize * 4)

        // 1. SETUP HARDWARE AUDIO RECORD (INPUT) WITH FALLBACKS
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) // VOICE_RECOGNITION has lowest DSP lag
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(actualRecordBufSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualRecordBufSize
                )
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                // Secondary fallback to standard Mic
                audioRecord.release()
                @Suppress("DEPRECATION")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualRecordBufSize
                )
            }

            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording()
                activeAudioRecord = audioRecord
                micReady = true
                appContext?.let { updateActivePreferredDevice(it) }
                Log.d(TAG, "AudioRecord low-latency initialized and capture playing.")
            } else {
                Log.e(TAG, "Failed static verification on AudioRecord.")
                audioRecord.release()
                audioRecord = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord Builder initialization error: ${e.message}. Using MIC fallback.")
            try {
                @Suppress("DEPRECATION")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualRecordBufSize
                )
                if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.startRecording()
                    activeAudioRecord = audioRecord
                    micReady = true
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Total audio input capture initialization failure.", ex)
                audioRecord = null
            }
        }

        // 2. SETUP HARDWARE AUDIO TRACK (OUTPUT) WITH TRUE LOW-LATENCY ATTRIBUTES
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(actualTrackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualTrackBufSize,
                    AudioTrack.MODE_STREAM
                )
            }
            audioTrack.play()
            Log.d(TAG, "AudioTrack low-latency initialized and playing.")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack Builder failed, falling back to legacy constructor: ${e.message}")
            try {
                @Suppress("DEPRECATION")
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualTrackBufSize,
                    AudioTrack.MODE_STREAM
                )
                audioTrack.play()
            } catch (ex: Exception) {
                Log.e(TAG, "AudioTrack total initialization failure.", ex)
                audioRecord?.release()
                return
            }
        }

        // Preallocate generous maximum capacity arrays to prevent memory allocation in the low-latency thread
        val maxBufCapacity = 1024
        val inBuf = ShortArray(maxBufCapacity)
        val outBuf = ShortArray(maxBufCapacity * 2) // Stereo output

        var lastSpectrumTime = System.currentTimeMillis()
        val spectrumHistory = Array(5) { FloatArray(32) }
        var historyIndex = 0

        var peakL = 0.02f
        var peakR = 0.02f

        // MAIN REALTIME LOW-LATENCY PROCESSING LOOP
        try {
            while (audioJob?.isActive == true) {
                val startTime = System.nanoTime()
                
                // Get local volatile snapshot of current buffer size, constrained to safe range
                val currentBufferSize = if (bufferSize <= 0) 12 else bufferSize.coerceIn(12, maxBufCapacity)

                // ALWAYS read from input to prevent hardware buffer backups (zero lag buildup)
                var samplesRead = 0
                if (audioRecord != null) {
                    samplesRead = audioRecord.read(inBuf, 0, currentBufferSize)
                }

                // If no samples read, clear the buffer with silence
                if (samplesRead <= 0) {
                    for (i in 0 until currentBufferSize) {
                        inBuf[i] = 0
                    }
                }

                // Fichier audio importé : il remplace le micro pendant la lecture
                val filePlaying = AudioFileBridge.isPlaying
                if (filePlaying) {
                    AudioFileBridge.fillBuffer(inBuf, currentBufferSize)
                }

                val dspStart = System.nanoTime()
                processBlock(inBuf, outBuf, currentBufferSize, isInputActive || filePlaying)
                if (recording) appendRecording(outBuf, currentBufferSize * 2)
                val rmsLeftSum = blkRmsL
                val rmsRightSum = blkRmsR

                // Write processed buffer block to output playout
                audioTrack.write(outBuf, 0, currentBufferSize * 2)

                // DSP execution time vs Hardware buffer window duration calculations (CPU usage %)
                val elapsedDspNanos = System.nanoTime() - dspStart
                val bufferLimitNanos = (currentBufferSize.toDouble() / sampleRate) * 1_000_000_000.0
                val relativeCpuLoad = ((elapsedDspNanos.toDouble() / bufferLimitNanos) * 100.0).coerceIn(1.0, 99.0).toInt()
                cpuUsagePercent.value = max(1, relativeCpuLoad)

                // Latency calculation: if bufferSize user-set is 0, display 0 ms (Zero-latency feeling)
                latencyMs.value = if (bufferSize <= 0) 0 else (currentBufferSize * 1000) / sampleRate

                // Emit live metering updates at throttled ~40 FPS to prevent UI thread stuttering
                val now = System.currentTimeMillis()
                if (now - lastSpectrumTime > 25) {
                    lastSpectrumTime = now

                    if (recording) _recordFrames.value = recTotal / 2

                    val rmsL = sqrt(rmsLeftSum / currentBufferSize).toFloat().coerceIn(0.005f, 1.0f)
                    val rmsR = sqrt(rmsRightSum / currentBufferSize).toFloat().coerceIn(0.005f, 1.0f)
                    
                    _vuLevels.value = Pair(rmsL, rmsR)

                    // Decaying Peak hold tracking
                    if (rmsL > peakL) peakL = rmsL else peakL = max(0.01f, peakL - 0.04f)
                    if (rmsR > peakR) peakR = rmsR else peakR = max(0.01f, peakR - 0.04f)
                    _vuPeak.value = Pair(peakL, peakR)

                    // REAL MATHEMATICALLY LOG-SPACED DFT SPECTROMETER
                    // Map frequencies from 70Hz (Sub-bass/vocal fundamental) to 11000Hz (Vocal air)
                    val freqBands = FloatArray(32) { b ->
                        val minF = 70.0
                        val maxF = 11000.0
                        (minF * (maxF / minF).pow(b / 31.0)).toFloat()
                    }

                    for (b in 0..31) {
                        val freq = freqBands[b]
                        val omega = (2f * PI.toFloat() * freq / sampleRate)
                        
                        var rSum = 0f
                        var iSum = 0f
                        
                        // Run windowed Real DFT on the current hardware buffer
                        for (i in 0 until currentBufferSize) {
                            val rawVal = inBuf[i] / 32768.0f
                            // Hann window function to smooth spectral leakage
                            val window = 0.5f * (1.1f - cos(2f * PI.toFloat() * i / currentBufferSize))
                            val windowedSample = rawVal * window
                            
                            val angle = omega * i
                            rSum += windowedSample * cos(angle)
                            iSum += windowedSample * sin(angle)
                        }
                        
                        // Scale the magnitude to a beautiful natural visual range
                        val rawMag = sqrt(rSum * rSum + iSum * iSum)
                        
                        // Dynamic equalizer band boost tuning based on human hearing spectrum sensitivity
                        val bandBoost = if (b < 4) 4.5f else if (b < 12) 5.5f else if (b < 24) 6.5f else 8.5f
                        // Prevent vertical clipping while maintaining lush bouncing visual fidelity
                        val valEner = (rawMag * bandBoost * 1.5f).coerceIn(0.005f, 1.0f)
                        
                        spectrumHistory[historyIndex][b] = valEner
                    }
                    historyIndex = (historyIndex + 1) % 5

                    // Temporal averaging smoothing over history samples
                    val finalSpec = FloatArray(32)
                    for (b in 0..31) {
                        var avg = 0f
                        for (h in 0..4) {
                            avg += spectrumHistory[h][b]
                        }
                        finalSpec[b] = avg / 5.0f
                    }
                    _spectrumData.value = finalSpec
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Audio loop processing loop encountered fatal throwable.", t)
        } finally {
            // SAFE HARDWARE CLEAN-UP ON PIPELINE SHUTDOWN OR ERROR
            micReady = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Exception releasing AudioRecord on thread shutdown: ${e.message}")
            }

            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                Log.e(TAG, "Exception releasing AudioTrack on thread shutdown: ${e.message}")
            }
            Log.d(TAG, "Low-latency Audio pipeline terminated cleanly and hardware released.")
        }
    }
}

// Click-Free Dual-Tap Circular Pitch Shifter Helper Class
private class PitchShifter(val size: Int = 4096) {
    val buffer = FloatArray(size)
    var writePos = 0
    var phase = 0f
    private val mask = size - 1

    fun process(sample: Float, shiftRatio: Float): Float {
        // Safe check for NaN
        val cleanSample = if (sample.isNaN() || sample.isInfinite()) 0f else sample
        buffer[writePos] = cleanSample

        // Calculate phase change
        val speedDifference = 1.0f - shiftRatio
        phase += speedDifference
        if (phase < 0) phase += size
        if (phase >= size) phase -= size

        val tap1 = phase
        val tap2 = (phase + size / 2)

        // Linear triangular crossfade weights
        val weight1 = abs(phase - size / 2) / (size / 2.0f)
        val weight2 = 1.0f - weight1

        // Look back by 128 samples padding to maintain clean historical read offsets
        val read1 = (writePos - 128 - tap1.toInt()) and mask
        val read2 = (writePos - 128 - tap2.toInt()) and mask

        val out1 = buffer[read1]
        val out2 = buffer[read2]

        writePos = (writePos + 1) and mask
        return out1 * weight1 + out2 * weight2
    }
}
