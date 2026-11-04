package com.example.model

enum class WaveTheme(val label: String) {
    DARK_NEON("Néon sombre"),
    CYBER_BLUE("Bleu cyber"),
    GOLD_PRO("Or pro"),
    RED_STUDIO("Rouge studio"),
    PURPLE_GALAXY("Galaxie violette"),
    EMERALD_PRO("Émeraude pro")
}

enum class PitchScale(val label: String) {
    CHROMATIC("Chromatique"),
    MAJOR("Majeure"),
    MINOR("Mineure"),
    PENTATONIC("Pentatonique")
}

enum class PitchKey(val label: String) {
    C("Do"), CD_CS("Do# / Réb"), D("Ré"), DE_DS("Ré# / Mib"), E("Mi"),
    F("Fa"), FG_FS("Fa# / Solb"), G("Sol"), GA_GS("Sol# / Lab"), A("La"),
    AB_AS("La# / Sib"), B("Si")
}

enum class PitchMode(val label: String) {
    NATURAL("Naturel"),
    POP("Pop"),
    HARD_TUNE("Tune dur"),
    ROBOT("Robot")
}

enum class ReverbType(val label: String) {
    A("Réverb A"),
    B("Réverb B (Salle)"),
    C("Réverb B (Plaque)"),
    D("Réverb B (Arène)"),
    E("Réverb B (Studio)")
}

// Global studio presets
enum class StudioPreset(val label: String) {
    KARAOKE_STUDIO("Karaoké studio"),
    DANGDUT_MODERN("Dangdut moderne"),
    MC_PROFESIONAL("MC professionnel"),
    PODCAST("Podcast"),
    LIVE_STREAMING("Streaming en direct"),
    VOKAL_JERNIH("Voix claire"),
    VOKAL_TEBAL("Voix épaisse"),
    KONSER_BESAR("Grand concert"),
    STUDIO_REKAMAN("Studio d'enregistrement"),
    MASJID("Mosquée"),
    CERAMAH("Prêche"),
    PRESENTER("Présentateur"),
    PENYANYI_PRIA("Chanteur"),
    PENYANYI_WANITA("Chanteuse"),
    BAND_LIVE("Groupe live"),
    ACOUSTIC("Acoustique"),
    EDM_VOCAL("Voix EDM"),
    ROCK_VOCAL("Voix rock")
}

// 12 Professional Audio Effect Racks
data class ParametricEqSettings(
    var isEnabled: Boolean = true,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var bands: FloatArray = FloatArray(31) { 0f }, // -12dB to +12dB
    var highPassHz: Float = 80f,                   // 20Hz - 500Hz
    var lowPassHz: Float = 15000f,                 // 5kHz - 20kHz
    var bellFilterHz: Float = 1000f,
    var bellFilterGainDb: Float = 0f,
    var shelfFilterGainDb: Float = 0f
) {
    fun reset() {
        bands = FloatArray(31) { 0f }
        highPassHz = 80f
        lowPassHz = 15000f
        bellFilterHz = 1000f
        bellFilterGainDb = 0f
        shelfFilterGainDb = 0f
    }
}

data class CompressorSettings(
    var isEnabled: Boolean = true,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var thresholdDb: Float = -24f,  // -60dB to 0dB
    var ratio: Float = 3f,          // 1:1 to 20:1
    var attackMs: Float = 15f,      // 0.1ms to 100ms
    var releaseMs: Float = 150f,    // 10ms to 1000ms
    var makeupGainDb: Float = 4f,   // 0dB to 24dB
    var kneeDb: Float = 5f          // 0dB to 12dB
) {
    fun reset() {
        thresholdDb = -24f
        ratio = 3f
        attackMs = 15f
        releaseMs = 150f
        makeupGainDb = 4f
        kneeDb = 5f
    }
}

data class LimiterSettings(
    var isEnabled: Boolean = false,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var thresholdDb: Float = -6f,   // -30dB to 0dB
    var ceilingDb: Float = -1f,     // -12dB to 0dB
    var releaseMs: Float = 100f     // 10ms to 500ms
) {
    fun reset() {
        thresholdDb = -6f
        ceilingDb = -1f
        releaseMs = 100f
    }
}

data class ReverbSettings(
    var isEnabled: Boolean = true,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var roomSize: Float = 65f,      // 0 to 100
    var width: Float = 75f,         // 0 to 100 (Stereo expansion)
    var decaySec: Float = 2.4f,     // 0.1s to 10s
    var damping: Float = 30f,       // 0 to 100
    var mixPercent: Float = 25f,    // 0% to 100%
    var preDelayMs: Float = 20f,    // 0ms to 200ms
    var reverbType: ReverbType = ReverbType.A
) {
    fun reset() {
        roomSize = 65f
        width = 75f
        decaySec = 2.4f
        damping = 30f
        mixPercent = 25f
        preDelayMs = 20f
        reverbType = ReverbType.A
    }
}

data class DelaySettings(
    var isEnabled: Boolean = true,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var timeMs: Float = 350f,       // 50ms to 2000ms
    var feedbackPercent: Float = 40f, // 0 to 95%
    var mixPercent: Float = 20f,    // 0% to 100%
    var isPingPong: Boolean = true,
    var stereoWidth: Float = 80f,   // 0 to 100%
    var isSync: Boolean = false
) {
    fun reset() {
        timeMs = 350f
        feedbackPercent = 40f
        mixPercent = 20f
        isPingPong = true
        stereoWidth = 80f
        isSync = false
    }
}

data class HarmonySettings(
    var isEnabled: Boolean = false,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var isAuto: Boolean = true,
    var voicesCount: Int = 3,       // 2, 3, 4, 6 voices
    var pitchShiftSemitones: Float = 4f, // -12 to +12
    var genderFormantFactor: Float = 50f, // 0 to 100 (Masculine / Feminine)
    var stereoWidth: Float = 70f,
    var humanizePercent: Float = 30f,
    var timingLagMs: Float = 25f
) {
    fun reset() {
        isAuto = true
        voicesCount = 3
        pitchShiftSemitones = 4f
        genderFormantFactor = 50f
        stereoWidth = 70f
        humanizePercent = 30f
        timingLagMs = 25f
    }
}

data class PitchCorrectionSettings(
    var isEnabled: Boolean = false,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var scale: PitchScale = PitchScale.MAJOR,
    var key: PitchKey = PitchKey.C,
    var retuneSpeedPercent: Float = 60f, // Fast (Auto-Tune) to Slow (Natural)
    var humanizePercent: Float = 40f,
    var flexTunePercent: Float = 50f,
    var mode: PitchMode = PitchMode.NATURAL
) {
    fun reset() {
        scale = PitchScale.MAJOR
        key = PitchKey.C
        retuneSpeedPercent = 60f
        humanizePercent = 40f
        flexTunePercent = 50f
        mode = PitchMode.NATURAL
    }
}

data class VocalEnhancerSettings(
    var isEnabled: Boolean = true,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var presence: Float = 40f,      // 0 to 100
    var clarity: Float = 50f,       // 0 to 100
    var air: Float = 30f,           // 0 to 100
    var warmth: Float = 60f,        // 0 to 100
    var brightness: Float = 45f     // 0 to 100
) {
    fun reset() {
        presence = 40f
        clarity = 50f
        air = 30f
        warmth = 60f
        brightness = 45f
    }
}

data class DeEsserSettings(
    var isEnabled: Boolean = false,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var frequencyHz: Float = 5500f, // 2000Hz - 9000Hz
    var thresholdDb: Float = -18f,  // -40dB to 0dB
    var amountPercent: Float = 50f  // 0 to 100
) {
    fun reset() {
        frequencyHz = 5500f
        thresholdDb = -18f
        amountPercent = 50f
    }
}

data class NoiseReductionSettings(
    var isEnabled: Boolean = true,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var noiseThresholdDb: Float = -45f, // -80dB to -30dB
    var hissReductionPercent: Float = 60f,
    var humReductionPercent: Float = 40f,
    var backgroundGateMs: Float = 150f
) {
    fun reset() {
        noiseThresholdDb = -45f
        hissReductionPercent = 60f
        humReductionPercent = 40f
        backgroundGateMs = 150f
    }
}

data class StereoImagerSettings(
    var isEnabled: Boolean = false,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var widthPercent: Float = 120f,       // 0 (Mono) to 200%
    var stereoExpandPercent: Float = 30f,  // 0 to 100
    var monoMakerHz: Float = 120f          // 20Hz to 500Hz
) {
    fun reset() {
        widthPercent = 120f
        stereoExpandPercent = 30f
        monoMakerHz = 120f
    }
}

data class ExciterSettings(
    var isEnabled: Boolean = false,
    var isBypassed: Boolean = false,
    var isLocked: Boolean = false,
    var warmFactor: Float = 20f,
    var brightFactor: Float = 30f,
    var tubeSaturationPercent: Float = 25f,
    var analogNoisePercent: Float = 10f
) {
    fun reset() {
        warmFactor = 20f
        brightFactor = 30f
        tubeSaturationPercent = 25f
        analogNoisePercent = 10f
    }
}

// Unified state representation of all effects
data class AllEffectsState(
    val eq: ParametricEqSettings = ParametricEqSettings(),
    val compressor: CompressorSettings = CompressorSettings(),
    val limiter: LimiterSettings = LimiterSettings(),
    val reverb: ReverbSettings = ReverbSettings(),
    val delay: DelaySettings = DelaySettings(),
    val harmony: HarmonySettings = HarmonySettings(),
    val pitchCorrection: PitchCorrectionSettings = PitchCorrectionSettings(),
    val enhancer: VocalEnhancerSettings = VocalEnhancerSettings(),
    val deEsser: DeEsserSettings = DeEsserSettings(),
    val noiseReduction: NoiseReductionSettings = NoiseReductionSettings(),
    val stereoImager: StereoImagerSettings = StereoImagerSettings(),
    val exciter: ExciterSettings = ExciterSettings()
)
