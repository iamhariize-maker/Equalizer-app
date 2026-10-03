package app.svan

import android.content.Context
import app.svan.model.AudioSettings
import app.svan.model.EqMode
import app.svan.model.EqState
import app.svan.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the EQ curve and audiophile settings.
 *
 * The UI writes here; this pushes to the curve engine synchronously (cheap, so
 * the graph is always exact) and to Engine A's DynamicsProcessing on a
 * background coroutine, conflated so a fast drag never queues up binder calls.
 * CaptureService (Engine B) observes [eq] and [settings] directly.
 */
object SvanRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: android.content.SharedPreferences

    private val _eq = MutableStateFlow(EqState())
    val eq: StateFlow<EqState> = _eq.asStateFlow()

    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()

    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())
    val userPresets: StateFlow<List<Preset>> = _userPresets.asStateFlow()

    /** Bumped whenever Engine A has applied the newest curve (for UI status). */
    private val _engineARevision = MutableStateFlow(0)
    val engineARevision: StateFlow<Int> = _engineARevision.asStateFlow()

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = context.applicationContext.getSharedPreferences("svan", Context.MODE_PRIVATE)
            prefs.getString("eq", null)?.let { s -> runCatching { _eq.value = EqState.fromJson(JSONObject(s)) } }
            prefs.getString("settings", null)?.let { s -> runCatching { _settings.value = AudioSettings.fromJson(JSONObject(s)) } }
            prefs.getString("presets", null)?.let { s ->
                runCatching {
                    val a = JSONArray(s)
                    _userPresets.value = List(a.length()) { Preset.fromJson(a.getJSONObject(it)) }
                }
            }
            applyCurve(_eq.value)
            EqController.globalEq.reconfigure(_settings.value.systemBands)
            initialized = true
            scope.launch {
                // StateFlow is already conflated: a slow binder update never queues stale curves.
                _eq.collect { state ->
                    EqController.globalEq.applyCurveFrom(EqController.curveEngine)
                    _engineARevision.update { it + 1 }
                    prefs.edit().putString("eq", state.toJson().toString()).apply()
                }
            }
        }
    }

    // ---- EQ edits (call from the UI thread) ----

    fun update(transform: (EqState) -> EqState) {
        val next = transform(_eq.value)
        applyCurve(next)
        _eq.value = next
    }

    fun applyPreset(p: Preset) = update {
        it.copy(mode = EqMode.PARAMETRIC, bands = p.bands, preampDb = p.preampDb, presetName = p.name, enabled = true)
    }

    /** Imports AutoEq / Equalizer APO text. Returns the number of bands, or 0 if nothing parsed. */
    fun importParametric(name: String, text: String): Int {
        val (preamp, bands) = NativeEngine.parseParametric(text)
        if (bands.isEmpty()) return 0
        val preset = Preset(name, preamp, bands.map {
            app.svan.model.Band(it.type, it.freqHz, it.gainDb, it.q, it.enabled)
        })
        saveUserPreset(preset)
        applyPreset(preset)
        return bands.size
    }

    fun saveUserPreset(p: Preset) {
        _userPresets.update { list -> list.filterNot { it.name == p.name } + p }
        persistPresets()
    }

    fun deleteUserPreset(name: String) {
        _userPresets.update { list -> list.filterNot { it.name == name } }
        persistPresets()
    }

    fun currentAsPreset(name: String): Preset {
        val s = _eq.value
        return Preset(name, s.preampDb, s.effectiveBands().ifEmpty { s.bands })
    }

    // ---- settings ----

    fun updateSettings(transform: (AudioSettings) -> AudioSettings) {
        val old = _settings.value
        val next = transform(old)
        _settings.value = next
        prefs.edit().putString("settings", next.toJson().toString()).apply()
        if (next.systemBands != old.systemBands) {
            scope.launch {
                EqController.globalEq.reconfigure(next.systemBands)
                EqController.globalEq.applyCurveFrom(EqController.curveEngine)
            }
        }
    }

    // ---- internals ----

    private fun applyCurve(s: EqState) {
        val engine = EqController.curveEngine
        engine.setBands(s.effectiveBands().map { it.toNative() })
        engine.setPreampDb(s.effectivePreampDb())
    }

    private fun persistPresets() {
        prefs.edit().putString("presets", JSONArray().apply { _userPresets.value.forEach { put(it.toJson()) } }.toString()).apply()
    }
}
