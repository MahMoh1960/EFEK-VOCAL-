package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioProcessor
import com.example.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class VocalStudioViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "VocalStudioViewModel"
    private val prefs = application.getSharedPreferences("bro_efek_vocal_prefs", Context.MODE_PRIVATE)

    // Main Audio Engine
    val audioProcessor = AudioProcessor()

    // Screen Tabs navigation enum
    enum class StudioTab(val label: String) {
        TRACKS("Rack studio"),
        ROUTING("Routage"),
        MIXER("Mixage"),
        SETTINGS("Réglages")
    }

    private val _currentTab = MutableStateFlow(StudioTab.TRACKS)
    val currentTab: StateFlow<StudioTab> = _currentTab.asStateFlow()

    // Themes
    private val _currentTheme = MutableStateFlow(WaveTheme.DARK_NEON)
    val currentTheme: StateFlow<WaveTheme> = _currentTheme.asStateFlow()

    // Effects state linked to the audio engine
    private val _effectsState = MutableStateFlow(AllEffectsState())
    val effectsState: StateFlow<AllEffectsState> = _effectsState.asStateFlow()

    // Microphone selection options
    enum class MicrophoneInput(val id: String, val label: String) {
        SYSTEM_DEFAULT("system_default", "Micro système/par défaut"),
        INTERNAL_MIC("internal_mic", "Micro interne"),
        HEADSET_MIC("headset_mic", "Micro casque/filaire"),
        BLUETOOTH_MIC("bluetooth_mic", "Micro Bluetooth"),
        USB_MIC("usb_mic", "Micro USB/externe")
    }

    private val _selectedMicInput = MutableStateFlow(MicrophoneInput.SYSTEM_DEFAULT)
    val selectedMicInput: StateFlow<MicrophoneInput> = _selectedMicInput.asStateFlow()

    // Monitoring
    private val _isInputActive = MutableStateFlow(false)
    val isInputActive: StateFlow<Boolean> = _isInputActive.asStateFlow()

    // Volume parameters
    private val _volInput = MutableStateFlow(0.8f)
    val volInput: StateFlow<Float> = _volInput.asStateFlow()

    private val _volVocal = MutableStateFlow(1.0f)
    val volVocal: StateFlow<Float> = _volVocal.asStateFlow()

    private val _volEffect = MutableStateFlow(0.5f)
    val volEffect: StateFlow<Float> = _volEffect.asStateFlow()

    private val _volMaster = MutableStateFlow(0.8f)
    val volMaster: StateFlow<Float> = _volMaster.asStateFlow()

    private val _bufferSize = MutableStateFlow(48)
    val bufferSize: StateFlow<Int> = _bufferSize.asStateFlow()

    // Selected Preset
    private val _selectedPreset = MutableStateFlow<StudioPreset?>(null)
    val selectedPreset: StateFlow<StudioPreset?> = _selectedPreset.asStateFlow()

    // Active expanded rack item index (-1 means none expanded, displaying list)
    private val _expandedRackIndex = MutableStateFlow<Int>(0) // Expand RACK 1 (EQ) by default
    val expandedRackIndex: StateFlow<Int> = _expandedRackIndex.asStateFlow()

    // Custom user presets list
    private val _customPresets = MutableStateFlow<List<String>>(emptyList())
    val customPresets: StateFlow<List<String>> = _customPresets.asStateFlow()

    init {
        // Load saved theme if any
        val savedTheme = prefs.getString("selected_theme", WaveTheme.DARK_NEON.name)
        _currentTheme.value = WaveTheme.values().find { it.name == savedTheme } ?: WaveTheme.DARK_NEON

        // Load saved buffer size
        val savedBufferSize = prefs.getInt("buffer_size", 48)
        _bufferSize.value = savedBufferSize
        audioProcessor.bufferSize = savedBufferSize

        // Load custom presets index
        loadCustomPresetsList()

        // Sync initial state with AudioProcessor
        syncProcessor()
        
        // Start engine loop
        audioProcessor.start(getApplication())
    }

    fun selectTab(tab: StudioTab) {
        _currentTab.value = tab
    }

    fun selectTheme(theme: WaveTheme) {
        _currentTheme.value = theme
        prefs.edit().putString("selected_theme", theme.name).apply()
    }

    fun toggleInputActive() {
        val newState = !_isInputActive.value
        _isInputActive.value = newState
        audioProcessor.isInputActive = newState
    }

    fun selectMicInput(micInput: MicrophoneInput) {
        _selectedMicInput.value = micInput
        audioProcessor.selectedMicInput = micInput
        audioProcessor.updateActivePreferredDevice(getApplication())
    }

    fun setExpandedRack(index: Int) {
        // Toggles expanding index
        _expandedRackIndex.value = if (_expandedRackIndex.value == index) -1 else index
    }

    fun setVolInput(vol: Float) {
        _volInput.value = vol
        audioProcessor.volInput = vol
    }

    fun setVolVocal(vol: Float) {
        _volVocal.value = vol
        audioProcessor.volVocal = vol
    }

    fun setVolEffect(vol: Float) {
        _volEffect.value = vol
        audioProcessor.volEffect = vol
    }

    fun setVolMaster(vol: Float) {
        _volMaster.value = vol
        audioProcessor.volMaster = vol
    }

    fun setBufferSize(v: Int) {
        _bufferSize.value = v
        audioProcessor.bufferSize = v
        prefs.edit().putInt("buffer_size", v).apply()
    }

    private fun syncProcessor() {
        audioProcessor.effectsState = _effectsState.value
        audioProcessor.volInput = _volInput.value
        audioProcessor.volVocal = _volVocal.value
        audioProcessor.volEffect = _volEffect.value
        audioProcessor.volMaster = _volMaster.value
        audioProcessor.bufferSize = _bufferSize.value
    }

    fun updateEffects(update: (AllEffectsState) -> Unit) {
        val currentState = _effectsState.value
        val newState = AllEffectsState(
            eq = currentState.eq.copy(bands = currentState.eq.bands.clone()),
            compressor = currentState.compressor.copy(),
            limiter = currentState.limiter.copy(),
            reverb = currentState.reverb.copy(),
            delay = currentState.delay.copy(),
            harmony = currentState.harmony.copy(),
            pitchCorrection = currentState.pitchCorrection.copy(),
            enhancer = currentState.enhancer.copy(),
            deEsser = currentState.deEsser.copy(),
            noiseReduction = currentState.noiseReduction.copy(),
            stereoImager = currentState.stereoImager.copy(),
            exciter = currentState.exciter.copy()
        )
        update(newState)
        _effectsState.value = newState
        audioProcessor.effectsState = newState
    }

    // Load any of the 18 pre-configured professional layouts
    fun applyStudioPreset(preset: StudioPreset) {
        _selectedPreset.value = preset
        updateEffects { state ->
            // Reset all first
            state.eq.reset()
            state.compressor.reset()
            state.limiter.reset()
            state.reverb.reset()
            state.delay.reset()
            state.harmony.reset()
            state.pitchCorrection.reset()
            state.enhancer.reset()
            state.deEsser.reset()
            state.noiseReduction.reset()
            state.stereoImager.reset()
            state.exciter.reset()

            when(preset) {
                StudioPreset.KARAOKE_STUDIO -> {
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 35f
                    state.reverb.decaySec = 2.8f
                    state.delay.isEnabled = true
                    state.delay.mixPercent = 22f
                    state.delay.timeMs = 380f
                    // Smooth warm EQ curve
                    state.eq.bands[3] = 2.0f; state.eq.bands[4] = 3.0f; state.eq.bands[15] = 1.0f; state.eq.bands[28] = 2.0f
                }
                StudioPreset.DANGDUT_MODERN -> {
                    state.delay.isEnabled = true
                    state.delay.mixPercent = 32f
                    state.delay.feedbackPercent = 55f
                    state.delay.timeMs = 430f
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 24f
                    state.reverb.decaySec = 2.0f
                    state.enhancer.isEnabled = true
                    state.enhancer.presence = 65f
                    state.enhancer.clarity = 60f
                }
                StudioPreset.MC_PROFESIONAL -> {
                    state.compressor.isEnabled = true
                    state.compressor.thresholdDb = -18f
                    state.compressor.ratio = 4f
                    state.enhancer.isEnabled = true
                    state.enhancer.warmth = 55f
                    state.enhancer.presence = 40f
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 10f
                    state.reverb.decaySec = 1.2f
                    state.eq.bands[3] = 3.0f // Thick low mid
                }
                StudioPreset.PODCAST -> {
                    state.compressor.isEnabled = true
                    state.compressor.thresholdDb = -22f
                    state.compressor.ratio = 5f
                    state.noiseReduction.isEnabled = true
                    state.noiseReduction.noiseThresholdDb = -50f
                    state.enhancer.isEnabled = true
                    state.enhancer.warmth = 65f
                    state.enhancer.presence = 15f
                    state.deEsser.isEnabled = true
                    state.deEsser.thresholdDb = -20f
                }
                StudioPreset.LIVE_STREAMING -> {
                    state.compressor.isEnabled = true
                    state.noiseReduction.isEnabled = true
                    state.limiter.isEnabled = true
                    state.enhancer.isEnabled = true
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 12f
                    state.reverb.decaySec = 1.4f
                }
                StudioPreset.VOKAL_JERNIH -> {
                    state.enhancer.isEnabled = true
                    state.enhancer.clarity = 75f
                    state.enhancer.air = 60f
                    state.enhancer.presence = 50f
                    state.eq.bands[24] = 2.5f; state.eq.bands[28] = 4.0f // Boost highs
                }
                StudioPreset.VOKAL_TEBAL -> {
                    state.enhancer.isEnabled = true
                    state.enhancer.warmth = 80f
                    state.eq.bands[3] = 3.5f; state.eq.bands[4] = 4.0f // Boost low mids/bass
                }
                StudioPreset.KONSER_BESAR -> {
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 48f
                    state.reverb.decaySec = 4.5f
                    state.reverb.roomSize = 95f
                    state.delay.isEnabled = true
                    state.delay.mixPercent = 25f
                    state.delay.timeMs = 500f
                    state.delay.feedbackPercent = 50f
                }
                StudioPreset.STUDIO_REKAMAN -> {
                    state.compressor.isEnabled = true
                    state.compressor.thresholdDb = -20f
                    state.compressor.makeupGainDb = 5f
                    state.reverb.isEnabled = true
                    state.reverb.reverbType = ReverbType.E // Studio
                    state.reverb.mixPercent = 15f
                    state.reverb.decaySec = 1.1f
                    state.stereoImager.isEnabled = true
                    state.stereoImager.widthPercent = 125f
                }
                StudioPreset.MASJID -> {
                    state.reverb.isEnabled = true
                    state.reverb.roomSize = 85f
                    state.reverb.decaySec = 4.8f
                    state.reverb.mixPercent = 45f
                    state.delay.isEnabled = true
                    state.delay.mixPercent = 20f
                    state.delay.timeMs = 400f
                    state.delay.feedbackPercent = 40f
                }
                StudioPreset.CERAMAH -> {
                    state.reverb.isEnabled = true
                    state.reverb.decaySec = 1.3f
                    state.reverb.mixPercent = 14f
                    state.compressor.isEnabled = true
                    state.noiseReduction.isEnabled = true
                }
                StudioPreset.PRESENTER -> {
                    state.compressor.isEnabled = true
                    state.enhancer.isEnabled = true
                    state.enhancer.presence = 45f
                    state.enhancer.clarity = 40f
                    state.noiseReduction.isEnabled = true
                }
                StudioPreset.PENYANYI_PRIA -> {
                    state.enhancer.isEnabled = true
                    state.enhancer.warmth = 55f
                    state.enhancer.presence = 35f
                    state.pitchCorrection.isEnabled = true
                    state.pitchCorrection.scale = PitchScale.MAJOR
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 22f
                }
                StudioPreset.PENYANYI_WANITA -> {
                    state.enhancer.isEnabled = true
                    state.enhancer.clarity = 65f
                    state.enhancer.air = 50f
                    state.pitchCorrection.isEnabled = true
                    state.pitchCorrection.scale = PitchScale.MAJOR
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 22f
                }
                StudioPreset.BAND_LIVE -> {
                    state.compressor.isEnabled = true
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 20f
                    state.delay.isEnabled = true
                    state.delay.timeMs = 320f
                    state.delay.mixPercent = 15f
                }
                StudioPreset.ACOUSTIC -> {
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 30f
                    state.reverb.decaySec = 2.5f
                    state.stereoImager.isEnabled = true
                    state.stereoImager.widthPercent = 130f
                }
                StudioPreset.EDM_VOCAL -> {
                    state.pitchCorrection.isEnabled = true
                    state.pitchCorrection.mode = PitchMode.HARD_TUNE
                    state.pitchCorrection.retuneSpeedPercent = 100f
                    state.harmony.isEnabled = true
                    state.harmony.isAuto = true
                    state.harmony.voicesCount = 3
                    state.delay.isEnabled = true
                    state.delay.isPingPong = true
                    state.delay.mixPercent = 25f
                }
                StudioPreset.ROCK_VOCAL -> {
                    state.compressor.isEnabled = true
                    state.compressor.thresholdDb = -26f // Deep heavy compression
                    state.compressor.ratio = 6f
                    state.exciter.isEnabled = true
                    state.exciter.brightFactor = 50f
                    state.exciter.tubeSaturationPercent = 45f // Tube distortion growl!
                    state.reverb.isEnabled = true
                    state.reverb.mixPercent = 20f
                }
            }
        }
        
        // Log preset change
        Log.d(TAG, "Applied preset: ${preset.label}")
    }

    // Save customized sliders as a new user profile
    fun saveUserPreset(name: String) {
        if (name.isBlank()) return
        try {
            val json = JSONObject()
            val state = _effectsState.value
            
            // Serialize simple relevant parameters
            json.put("eq_highpass", state.eq.highPassHz)
            json.put("eq_lowpass", state.eq.lowPassHz)
            json.put("comp_thresh", state.compressor.thresholdDb)
            json.put("comp_ratio", state.compressor.ratio)
            json.put("reverb_mix", state.reverb.mixPercent)
            json.put("reverb_decay", state.reverb.decaySec)
            json.put("delay_mix", state.delay.mixPercent)
            json.put("delay_time", state.delay.timeMs)
            json.put("autotune_active", state.pitchCorrection.isEnabled)
            json.put("autotune_retune", state.pitchCorrection.retuneSpeedPercent)

            // Save JSON string in SharedPrefs
            prefs.edit().putString("custom_preset_$name", json.toString()).apply()

            // Update custom preset catalog
            val updatedList = _customPresets.value.toMutableList()
            if (!updatedList.contains(name)) {
                updatedList.add(name)
                saveCustomPresetsList(updatedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom preset: ${e.message}")
        }
    }

    fun loadUserPreset(name: String) {
        val jsonStr = prefs.getString("custom_preset_$name", null) ?: return
        try {
            val json = JSONObject(jsonStr)
            updateEffects { state ->
                state.eq.highPassHz = json.optDouble("eq_highpass", 80.0).toFloat()
                state.eq.lowPassHz = json.optDouble("eq_lowpass", 15000.0).toFloat()
                state.compressor.thresholdDb = json.optDouble("comp_thresh", -24.0).toFloat()
                state.compressor.ratio = json.optDouble("comp_ratio", 3.0).toFloat()
                state.reverb.mixPercent = json.optDouble("reverb_mix", 25.0).toFloat()
                state.reverb.decaySec = json.optDouble("reverb_decay", 2.4).toFloat()
                state.delay.mixPercent = json.optDouble("delay_mix", 20.0).toFloat()
                state.delay.timeMs = json.optDouble("delay_time", 350.0).toFloat()
                state.pitchCorrection.isEnabled = json.optBoolean("autotune_active", false)
                state.pitchCorrection.retuneSpeedPercent = json.optDouble("autotune_retune", 60.0).toFloat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading preset $name: ${e.message}")
        }
    }

    fun deleteUserPreset(name: String) {
        prefs.edit().remove("custom_preset_$name").apply()
        val updatedList = _customPresets.value.filter { it != name }
        saveCustomPresetsList(updatedList)
    }

    private fun loadCustomPresetsList() {
        val s = prefs.getString("custom_presets_index", "") ?: ""
        if (s.isNotEmpty()) {
            _customPresets.value = s.split(",").filter { it.isNotEmpty() }
        } else {
            _customPresets.value = emptyList()
        }
    }

    private fun saveCustomPresetsList(list: List<String>) {
        _customPresets.value = list
        val joined = list.joinToString(",")
        prefs.edit().putString("custom_presets_index", joined).apply()
    }

    override fun onCleared() {
        super.onCleared()
        audioProcessor.stop()
    }
}
