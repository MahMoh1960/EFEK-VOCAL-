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
                    val gainDbGain = - (20.0 * log10(compEnvL.toDouble() / thresh)).toFloat() *
